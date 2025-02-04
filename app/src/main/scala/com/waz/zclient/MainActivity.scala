/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient

import android.content.Intent
import android.content.Intent._
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.{Color, Paint, PixelFormat}
import android.os.{Build, Bundle}
import android.support.v4.app.{Fragment, FragmentTransaction}
import com.waz.content.UserPreferences._
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.UserData.ConnectionStatus.{apply => _}
import com.waz.model.{ConvId, UserId}
import com.waz.service.AccountManager.ClientRegistrationState.{LimitReached, PasswordMissing, Registered, Unregistered}
import com.waz.service.ZMessaging.clock
import com.waz.service.{AccountManager, AccountsService, ZMessaging}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.Signal
import com.waz.utils.{RichInstant, returning}
import com.waz.zclient.Intents._
import com.waz.zclient.SpinnerController.{Hide, Show}
import com.waz.zclient.appentry.AppEntryActivity
import com.waz.zclient.calling.controllers.CallStartController
import com.waz.zclient.common.controllers.global.{AccentColorController, KeyboardController, PasswordController}
import com.waz.zclient.common.controllers.{SharingController, UserAccountsController}
import com.waz.zclient.controllers.navigation.{NavigationControllerObserver, Page}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.deeplinks.DeepLinkService.Error.{InvalidToken, SSOLoginTooManyAccounts}
import com.waz.zclient.deeplinks.{DeepLink, DeepLinkService}
import com.waz.zclient.fragments.ConnectivityFragment
import com.waz.zclient.log.LogUI._
import com.waz.zclient.messages.controllers.NavigationController
import com.waz.zclient.pages.main.MainPhoneFragment
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController
import com.waz.zclient.pages.startup.UpdateFragment
import com.waz.zclient.participants.ParticipantsController
import com.waz.zclient.preferences.PreferencesActivity
import com.waz.zclient.preferences.dialogs.ChangeHandleFragment
import com.waz.zclient.tracking.UiTrackingController
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.StringUtils.TextDrawing
import com.waz.zclient.utils.{Emojis, IntentUtils, ViewUtils}
import com.waz.zclient.views.LoadingIndicatorView

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

class MainActivity extends BaseActivity
  with CallingBannerActivity
  with UpdateFragment.Container
  with NavigationControllerObserver
  with OtrDeviceLimitFragment.Container
  with SetHandleFragment.Container
  with DerivedLogTag {

  implicit val cxt = this

  import Threading.Implicits.Ui

  private lazy val zms                    = inject[Signal[ZMessaging]]
  private lazy val account                = inject[Signal[Option[AccountManager]]]
  private lazy val accountsService        = inject[AccountsService]
  private lazy val sharingController      = inject[SharingController]
  private lazy val accentColorController  = inject[AccentColorController]
  private lazy val callStart              = inject[CallStartController]
  private lazy val conversationController = inject[ConversationController]
  private lazy val userAccountsController = inject[UserAccountsController]
  private lazy val spinnerController      = inject[SpinnerController]
  private lazy val passwordController     = inject[PasswordController]
  private lazy val deepLinkService        = inject[DeepLinkService]
  private lazy val participantsController = inject[ParticipantsController]

  override def onAttachedToWindow() = {
    super.onAttachedToWindow()
    getWindow.setFormat(PixelFormat.RGBA_8888)
  }

  override def onCreate(savedInstanceState: Bundle) = {
    Option(getActionBar).foreach(_.hide())
    super.onCreate(savedInstanceState)

    //Prevent drawing the default background to reduce overdraw
    getWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT))
    setContentView(R.layout.main)

    ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, this)

    val fragmentManager = getSupportFragmentManager
    initializeControllers()

    if (savedInstanceState == null) {
      val fragmentTransaction = fragmentManager.beginTransaction
      fragmentTransaction.add(R.id.fl__offline__container, ConnectivityFragment(), ConnectivityFragment.FragmentTag)
      fragmentTransaction.commit
    } else getControllerFactory.getNavigationController.onActivityCreated(savedInstanceState)

    accentColorController.accentColor.map(_.color).onUi(
      getControllerFactory.getUserPreferencesController.setLastAccentColor
    )

    handleIntent(getIntent)

    val currentlyDarkTheme = themeController.darkThemeSet.currentValue.contains(true)

    themeController.darkThemeSet.onUi {
      case theme if theme != currentlyDarkTheme =>
        info(l"restartActivity")
        finish()
        startActivity(getIntent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
      case _ =>
    }

    userAccountsController.onAllLoggedOut.onUi {
      case true =>
        getControllerFactory.getPickUserController.hideUserProfile()
        getControllerFactory.getNavigationController.resetPagerPositionToDefault()
        finish()
        startActivity(returning(new Intent(this, classOf[AppEntryActivity]))(_.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)))
      case false =>
    }

    ForceUpdateActivity.checkBlacklist(this)

    val loadingIndicator = findViewById[LoadingIndicatorView](R.id.progress_spinner)

    spinnerController.spinnerShowing.onUi {
      case Show(animation, forcedIsDarkTheme)=>
        themeController.darkThemeSet.head.foreach(theme => loadingIndicator.show(animation, forcedIsDarkTheme.getOrElse(theme), 300))(Threading.Ui)
      case Hide(Some(message))=> loadingIndicator.hideWithMessage(message, 750)
      case Hide(_) => loadingIndicator.hide()
    }

  }

  override def onStart() = {
    getControllerFactory.getNavigationController.addNavigationControllerObserver(this)
    inject[NavigationController].mainActivityActive.mutate(_ + 1)

    super.onStart()

    if (!getControllerFactory.getUserPreferencesController.hasCheckedForUnsupportedEmojis(Emojis.VERSION))
      Future(checkForUnsupportedEmojis())(Threading.Background)

    import DeepLink.{logTag => _, _}
    import DeepLinkService._
    deepLinkService.checkForDeepLink(getIntent).foreach {
      case DoNotOpenDeepLink(SSOLogin, InvalidToken) =>
        showErrorDialog(R.string.sso_signin_wrong_code_title, R.string.sso_signin_wrong_code_message)
        startFirstFragment()

      case DoNotOpenDeepLink(SSOLogin, SSOLoginTooManyAccounts) =>
        showErrorDialog(R.string.sso_signin_max_accounts_title, R.string.sso_signin_max_accounts_message)
        startFirstFragment()

      case OpenDeepLink(SSOLoginToken(token), _) =>
        openSignUpPage(Some(token))

      case OpenDeepLink(UserToken(userId), UserTokenInfo(connected, currentTeamMember)) =>
        lazy val pickUserController = inject[IPickUserController]
        pickUserController.hideUserProfile()
        if (connected || currentTeamMember) {
          CancellableFuture.delay(750.millis).map { _ =>
            userAccountsController.getOrCreateAndOpenConvFor(userId)
              .foreach { _ =>
                participantsController.onShowParticipantsWithUserId ! userId
              }
          }
        } else {
          pickUserController.showUserProfile(userId)
        }

      case DoNotOpenDeepLink(Conversation, _) =>
        showErrorDialog(R.string.deep_link_conversation_error_title, R.string.deep_link_conversation_error_message)
        startFirstFragment()

      case OpenDeepLink(ConversationToken(convId), _) =>
        switchConversation(convId)

      case _ => startFirstFragment()
    }(Threading.Ui)
  }

  override protected def onResume() = {
    super.onResume()
    Option(ZMessaging.currentGlobal).foreach(_.googleApi.checkGooglePlayServicesAvailable(this))
  }

  private def openSignUpPage(ssoToken: Option[String] = None): Unit = {
    verbose(l"openSignUpPage(${ssoToken.map(showString)})")
    userAccountsController.ssoToken ! ssoToken
    startActivity(new Intent(getApplicationContext, classOf[AppEntryActivity]))
    finish()
  }


  def startFirstFragment(): Unit =
    account.head.flatMap {
      case Some(am) =>
        am.getOrRegisterClient().map {
          case Right(Registered(_)) =>
            for {
              _             <- passwordController.setPassword(None)
              z             <- zms.head
              self          <- z.users.selfUser.head
              isLogin       <- z.userPrefs(IsLogin).apply()
              isNewClient   <- z.userPrefs(IsNewClient).apply()
              pendingPw     <- z.userPrefs(PendingPassword).apply()
              pendingEmail  <- z.userPrefs(PendingEmail).apply()
              ssoLogin      <- accountsService.activeAccount.map(_.exists(_.ssoId.isDefined)).head
            } yield {
                val (f, t) =
                  if (ssoLogin) {
                    if (self.handle.isEmpty)                  (SetHandleFragment(), SetHandleFragment.Tag)
                    else                                      (new MainPhoneFragment, MainPhoneFragment.Tag)
                  }
                  else if (self.email.isDefined && pendingPw) (SetOrRequestPasswordFragment(self.email.get), SetOrRequestPasswordFragment.Tag)
                  else if (pendingEmail.isDefined)            (VerifyEmailFragment(pendingEmail.get), VerifyEmailFragment.Tag)
                  else if (self.email.isEmpty && isLogin && isNewClient && self.phone.isDefined)
                                                              (AddEmailFragment(), AddEmailFragment.Tag)
                  else if (self.handle.isEmpty)               (SetHandleFragment(), SetHandleFragment.Tag)
                  else                                        (new MainPhoneFragment, MainPhoneFragment.Tag)
                replaceMainFragment(f, t, addToBackStack = false)
            }

          case Right(LimitReached) =>
            for {
              self         <- am.getSelf
              pendingPw    <- am.storage.userPrefs(PendingPassword).apply()
              pendingEmail <- am.storage.userPrefs(PendingEmail).apply()
              ssoLogin     <- accountsService.activeAccount.map(_.exists(_.ssoId.isDefined)).head
            } yield {
              val (f, t) =
                if (ssoLogin)                               (OtrDeviceLimitFragment.newInstance, OtrDeviceLimitFragment.Tag)
                else if (self.email.isDefined && pendingPw) (SetOrRequestPasswordFragment(self.email.get), SetOrRequestPasswordFragment.Tag)
                else if (pendingEmail.isDefined)            (VerifyEmailFragment(pendingEmail.get), VerifyEmailFragment.Tag)
                else if (self.email.isEmpty)                (AddEmailFragment(), AddEmailFragment.Tag)
                else                                        (OtrDeviceLimitFragment.newInstance, OtrDeviceLimitFragment.Tag)
              replaceMainFragment(f, t, addToBackStack = false)
            }

          case Right(PasswordMissing) =>
            for {
              self         <- am.getSelf
              pendingEmail <- am.storage.userPrefs(PendingEmail).apply()
              ssoLogin     <- accountsService.activeAccount.map(_.exists(_.ssoId.isDefined)).head
            } {
              val (f, t) =
                if (ssoLogin) {
                  if (self.handle.isEmpty)       (SetHandleFragment(), SetHandleFragment.Tag)
                  else                           (new MainPhoneFragment, MainPhoneFragment.Tag)
                }
                else if (self.email.isDefined)   (SetOrRequestPasswordFragment(self.email.get, hasPassword = true), SetOrRequestPasswordFragment.Tag)
                else if (pendingEmail.isDefined) (VerifyEmailFragment(pendingEmail.get, hasPassword = true), VerifyEmailFragment.Tag)
                else                             (AddEmailFragment(hasPassword = true), AddEmailFragment.Tag)
              replaceMainFragment(f, t, addToBackStack = false)
            }
          case Right(Unregistered) => warn(l"This shouldn't happen, going back to sign in..."); Future.successful(openSignUpPage())
          case Left(_) => showGenericErrorDialog()
        }
      case _ =>
        warn(l"No logged in account, sending to Sign in")
        Future.successful(openSignUpPage())
    }

  def replaceMainFragment(fragment: Fragment, newTag: String, reverse: Boolean = false, addToBackStack: Boolean = true): Unit = {

    import scala.collection.JavaConverters._
    val oldTag = getSupportFragmentManager.getFragments.asScala.toList.flatMap(Option(_)).lastOption.flatMap {
      case _: SetOrRequestPasswordFragment => Some(SetOrRequestPasswordFragment.Tag)
      case _: VerifyEmailFragment          => Some(VerifyEmailFragment.Tag)
      case _: AddEmailFragment             => Some(AddEmailFragment.Tag)
      case _ => None
    }
    verbose(l"replaceMainFragment: ${oldTag.map(redactedString)} -> ${redactedString(newTag)}")

    val (in, out) = (MainActivity.isSlideAnimation(oldTag, newTag), reverse) match {
      case (true, true)  => (R.anim.fragment_animation_second_page_slide_in_from_left_no_alpha, R.anim.fragment_animation_second_page_slide_out_to_right_no_alpha)
      case (true, false) => (R.anim.fragment_animation_second_page_slide_in_from_right_no_alpha, R.anim.fragment_animation_second_page_slide_out_to_left_no_alpha)
      case _             => (R.anim.fade_in, R.anim.fade_out)
    }

    val frag = Option(getSupportFragmentManager.findFragmentByTag(newTag)) match {
      case Some(f) => returning(f)(_.setArguments(fragment.getArguments))
      case _       => fragment
    }

    val transaction = getSupportFragmentManager
      .beginTransaction
      .setCustomAnimations(in, out)
      .replace(R.id.fl_main_content, frag, newTag)
    if (addToBackStack) transaction.addToBackStack(newTag)
    transaction.commit
    spinnerController.hideSpinner()
  }

  def removeFragment(fragment: Fragment): Unit = {
    val transaction = getSupportFragmentManager
      .beginTransaction
      .remove(fragment)
    transaction.commit
  }

  override protected def onSaveInstanceState(outState: Bundle) = {
    getControllerFactory.getNavigationController.onSaveInstanceState(outState)
    super.onSaveInstanceState(outState)
  }

  override def onStop() = {
    super.onStop()
    getControllerFactory.getNavigationController.removeNavigationControllerObserver(this)
    inject[NavigationController].mainActivityActive.mutate(_ - 1)
  }

  override def onBackPressed(): Unit = {
    Option(getSupportFragmentManager.findFragmentById(R.id.fl_main_content)).foreach {
      case f: OnBackPressedListener if f.onBackPressed() => //
      case _ => super.onBackPressed()
    }
  }

  override protected def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = {
    super.onActivityResult(requestCode, resultCode, data)
    Option(ZMessaging.currentGlobal).foreach(_.googleApi.onActivityResult(requestCode, resultCode))
    Option(getSupportFragmentManager.findFragmentById(R.id.fl_main_content)).foreach(_.onActivityResult(requestCode, resultCode, data))

    if (requestCode == PreferencesActivity.SwitchAccountCode && data != null) {
      Option(data.getStringExtra(PreferencesActivity.SwitchAccountExtra)).foreach { extraStr =>
        accountsService.setAccount(Some(UserId(extraStr)))
      }
    }
  }

  override protected def onNewIntent(intent: Intent) = {
    super.onNewIntent(intent)
    verbose(l"onNewIntent: $intent")

    if (IntentUtils.isPasswordResetIntent(intent)) onPasswordWasReset()

    setIntent(intent)
    handleIntent(intent)
  }

  private def initializeControllers() = {
    //Ensure tracking is started
    inject[UiTrackingController]
    inject[KeyboardController]
    // Here comes code for adding other dependencies to controllers...
    getControllerFactory.getNavigationController.setIsLandscape(isInLandscape(this))
  }

  private def onPasswordWasReset() =
    for {
      Some(am) <- accountsService.activeAccountManager.head
      _        <- am.auth.onPasswordReset(emailCredentials = None)
    } yield {}

  def handleIntent(intent: Intent) = {
    verbose(l"handleIntent: ${RichIntent(intent)}")

    def clearIntent() = {
      intent.clearExtras()
      setIntent(intent)
    }

    intent match {
      case NotificationIntent(accountId, convId, startCall) =>
        verbose(l"notification intent, accountId: $accountId, convId: $convId")
        val switchAccount = {
          accountsService.activeAccount.head.flatMap {
            case Some(acc) if intent.accountId.contains(acc.id) => Future.successful(false)
            case _ => accountsService.setAccount(intent.accountId).map(_ => true)
          }
        }

        switchAccount.flatMap { _ =>
          (intent.convId match {
            case Some(id) => switchConversation(id, startCall)
            case _ =>        Future.successful({})
          }).map(_ => clearIntent())(Threading.Ui)
        }

        try {
          val t = clock.instant()
          if (Await.result(switchAccount, 2.seconds)) verbose(l"Account switched before resuming activity lifecycle. Took ${t.until(clock.instant()).toMillis} ms")
        } catch {
          case NonFatal(e) => error(l"Failed to switch accounts", e)
        }

      case SharingIntent() =>
        for {
          convs <- sharingController.targetConvs.head
          exp   <- sharingController.ephemeralExpiration.head
          _     <- sharingController.sendContent(this)
          _     <- if (convs.size == 1) switchConversation(convs.head) else Future.successful({})
        } yield clearIntent()

      case OpenPageIntent(page) => page match {
        case Intents.Page.Settings =>
          startActivityForResult(PreferencesActivity.getDefaultIntent(this), PreferencesActivity.SwitchAccountCode)
          clearIntent()
        case _ => error(l"Unknown page: ${redactedString(page)} - ignoring intent")
      }

      case _ => setIntent(intent)
    }
  }

  def onPageVisible(page: Page) =
    getControllerFactory.getGlobalLayoutController.setSoftInputModeForPage(page)

  def onInviteRequestSent(conversation: String) = {
    info(l"onInviteRequestSent(${redactedString(conversation)})")
    conversationController.selectConv(Option(new ConvId(conversation)), ConversationChangeRequester.INVITE)
  }

  override def logout() = {
    accountsService.activeAccountId.head.flatMap(_.fold(Future.successful({}))(accountsService.logout)).map { _ =>
      startFirstFragment()
    } (Threading.Ui)
  }

  def manageDevices() = startActivity(ShowDevicesIntent(this))

  def dismissOtrDeviceLimitFragment() = withFragmentOpt(OtrDeviceLimitFragment.Tag)(_.foreach(removeFragment))

  private def checkForUnsupportedEmojis() =
    for {
      cf <- Option(getControllerFactory) if !cf.isTornDown
      prefs <- Option(cf.getUserPreferencesController)
    } {
      val paint = new Paint
      val template = returning(new TextDrawing)(_.set("\uFFFF")) // missing char
      val check = new TextDrawing

      val missing = Emojis.getAllEmojisSortedByCategory.asScala.flatten.filter { emoji =>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
          !paint.hasGlyph(emoji)
        else {
          check.set(emoji)
          template == check
        }
      }

      if (missing.nonEmpty) prefs.setUnsupportedEmoji(missing.asJava, Emojis.VERSION)
    }

  override def onChooseUsernameChosen(): Unit =
    getSupportFragmentManager
      .beginTransaction
      .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
      .add(ChangeHandleFragment.newInstance("", cancellable = false), ChangeHandleFragment.Tag)
      .addToBackStack(ChangeHandleFragment.Tag)
      .commit

  override def onUsernameSet(): Unit = replaceMainFragment(new MainPhoneFragment, MainPhoneFragment.Tag, addToBackStack = false)

  private def switchConversation(convId: ConvId, call: Boolean = false) =
    CancellableFuture.delay(750.millis).map { _ =>
      verbose(l"setting conversation: $convId")
      conversationController.selectConv(convId, ConversationChangeRequester.INTENT).foreach { _ =>
        if (call)
          for {
            Some(acc) <- account.map(_.map(_.userId)).head
            _         <- callStart.startCall(acc, convId)
          } yield {}
      }
    } (Threading.Ui).future

}

object MainActivity {
  val ClientRegStateArg: String = "ClientRegStateArg"

  private val slideAnimations = Set(
    (SetOrRequestPasswordFragment.Tag, VerifyEmailFragment.Tag),
    (SetOrRequestPasswordFragment.Tag,  AddEmailFragment.Tag),
    (VerifyEmailFragment.Tag, AddEmailFragment.Tag)
  )

  private def isSlideAnimation(oldTag: Option[String], newTag: String) = oldTag.fold(false) { old =>
    slideAnimations.contains((old, newTag)) || slideAnimations.contains((newTag, old))
  }
}

