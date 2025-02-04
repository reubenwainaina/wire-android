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
package com.waz.zclient.participants.fragments

import android.content.Context
import android.os.Bundle
import android.support.v7.widget.Toolbar
import android.view._
import android.widget.TextView
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.utils.returning
import com.waz.zclient.ManagerFragment.Page
import com.waz.zclient.common.controllers.ThemeController
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.conversation.creation.{AddParticipantsFragment, CreateConversationController}
import com.waz.zclient.participants.ParticipantsController
import com.waz.zclient.utils.ContextUtils.{getColor, getDimenPx, getDrawable}
import com.waz.zclient.utils.{ContextUtils, RichView, ViewUtils}
import com.waz.zclient.{FragmentHelper, ManagerFragment, R}

class ParticipantHeaderFragment extends FragmentHelper {
  implicit def cxt: Context = getActivity

  private lazy val participantsController = inject[ParticipantsController]
  private lazy val themeController        = inject[ThemeController]
  private lazy val newConvController      = inject[CreateConversationController]
  private lazy val accentColor            = inject[AccentColorController].accentColor.map(_.color)

  private lazy val page = Option(getParentFragment) match {
    case Some(f: ManagerFragment) => f.currentContent
    case _                        => Signal.const(Option.empty[Page])
  }

  private lazy val pageTag = page.map(_.map(_.tag))

  private lazy val addingUsers = pageTag.map(_.contains(AddParticipantsFragment.Tag))

  private lazy val toolbar = returning(view[Toolbar](R.id.t__participants__toolbar)) { vh =>
    (for {
      p    <- page
      dark <- themeController.darkThemeSet
    } yield
      p match {
        case Some(Page(AddParticipantsFragment.Tag, _)) => Some(if (dark) R.drawable.ic_action_close_light else R.drawable.ic_action_close_dark)
        case Some(Page(_, false)) => Some(if (dark) R.drawable.action_back_light else R.drawable.action_back_dark)
        case _ => None
      })
      .onUi { icon =>
        vh.foreach { v => icon match {
          case Some(res) => v.setNavigationIcon(res)
          case None      => v.setNavigationIcon(null) //can't squash these calls - null needs to be of type Drawable, not int
        }}
      }
  }

  private lazy val potentialMemberCount =
    for {
      members         <- participantsController.otherParticipants
      newUsers        <- newConvController.users
      newIntegrations <- newConvController.integrations
    } yield (members ++ newUsers).size + newIntegrations.size + 1


  private lazy val confButton = returning(view[TextView](R.id.confirmation_button)) { vh =>

    val confButtonEnabled = Signal(newConvController.users.map(_.size), newConvController.integrations.map(_.size), potentialMemberCount).map {
      case (newUsers, newIntegrations, potential) => (newUsers > 0 || newIntegrations > 0) && potential <= ConversationController.MaxParticipants
    }
    confButtonEnabled.onUi(e => vh.foreach(_.setEnabled(e)))

    confButtonEnabled.flatMap {
      case false => Signal.const(getColor(R.color.teams_inactive_button))
      case _ => accentColor
    }.onUi(c => vh.foreach(_.setTextColor(c)))

    addingUsers.onUi(vis => vh.foreach(_.setVisible(vis)))
    vh.onClick { _ =>
      newConvController.addUsersToConversation()
      getActivity.onBackPressed()
    }
  }

  private lazy val closeButton = returning(view[TextView](R.id.close_button)) { vh =>
    addingUsers.map(!_).onUi(vis => vh.foreach(_.setVisible(vis)))
    vh.onClick(_ => participantsController.onShowAnimations ! true)
  }

  private lazy val headerReadOnlyTextView = returning(view[TextView](R.id.participants__header)) { vh =>
    pageTag.flatMap {
      case Some(GroupParticipantsFragment.Tag | GuestOptionsFragment.Tag) =>
        Signal.const(getString(R.string.participants_details_header_title))
      case Some(EphemeralOptionsFragment.Tag) =>
        Signal.const(getString(R.string.ephemeral_message__options_header))
      case Some(AddParticipantsFragment.Tag) =>
        Signal(newConvController.users, newConvController.integrations).map {
          case (u, i) if u.isEmpty && i.isEmpty => getString(R.string.add_participants_empty_header)
          case (u, i) => getString(R.string.add_participants_count_header, (u.size + i.size).toString)
        }
      case Some(AllGroupParticipantsFragment.Tag) =>
        Signal.const(getString(R.string.participant_search_title))
      case _ =>
        Signal.const(getString(R.string.empty_string))
    }.onUi(t => vh.foreach { view =>
      view.setVisible(t.nonEmpty)
      view.setText(t)
    })
  }

  private lazy val headerUsername = returning(view[TextView](R.id.participants__header__username)) { vh =>
    pageTag.onUi {
      case Some(SingleParticipantFragment.Tag) =>
        participantsController.otherParticipant.head.foreach { user =>
          vh.foreach { view =>
            view.setVisible(true)
            view.setText(user.getDisplayName)
            val shield = if (user.isVerified) Option(getDrawable(R.drawable.shield_full)) else None

            shield.foreach { sh =>
              val pushDown = getDimenPx(R.dimen.wire__padding__1)
              sh.setBounds(0, pushDown, sh.getIntrinsicWidth, sh.getIntrinsicHeight + pushDown)
              view.setCompoundDrawablePadding(getDimenPx(R.dimen.wire__padding__tiny))
            }
            val old = view.getCompoundDrawables
            view.setCompoundDrawablesRelative(shield.orNull, old(1), old(2), old(3))
            view.setContentDescription(if (user.isVerified) "verified" else "unverified")
          }
        }(Threading.Ui)
      case _ =>
        vh.foreach(_.setVisible(false))
    }
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)

    potentialMemberCount.map(_ > ConversationController.MaxParticipants).onUi {
      case true =>
        import com.waz.threading.Threading.Implicits.Ui
        participantsController.otherParticipants.map(_.size).head.foreach { others =>
          val remaining = ConversationController.MaxParticipants - others - 1
          ViewUtils.showAlertDialog(getContext,
            getString(R.string.max_participants_alert_title),
            ContextUtils.getString(R.string.max_participants_add_alert_message, remaining.toString),
            getString(android.R.string.ok), null, true)
        }
      case _ =>
    }
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) =
    inflater.inflate(R.layout.fragment_participants_header, container, false)

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)

    toolbar
    headerReadOnlyTextView
    headerUsername
    closeButton
    confButton
  }

  override def onResume(): Unit = {
    super.onResume()
    toolbar.foreach(_.setNavigationOnClickListener(new View.OnClickListener() {
      override def onClick(v: View): Unit = getActivity.onBackPressed()
    }))
  }

  override def onPause(): Unit = {
    toolbar.foreach(_.setNavigationOnClickListener(null))
    super.onPause()
  }
}

object ParticipantHeaderFragment {
  val TAG: String = classOf[ParticipantHeaderFragment].getName

  def newInstance: ParticipantHeaderFragment = new ParticipantHeaderFragment
}
