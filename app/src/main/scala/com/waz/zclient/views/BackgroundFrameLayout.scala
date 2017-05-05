/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
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
package com.waz.zclient.views

import android.content.Context
import android.graphics._
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.widget.ImageView
import com.waz.service.ZMessaging
import com.waz.utils.events.Signal
import com.waz.zclient.ViewHelper
import com.waz.zclient.ui.utils.ColorUtils
import com.waz.zclient.utils.{LayoutSpec, ViewUtils}
import com.waz.zclient.views.ImageAssetDrawable.{RequestBuilder, ScaleType}
import com.waz.zclient.views.ImageController.{ImageSource, WireImage}

class BackgroundFrameLayout(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends ImageView(context, attrs, defStyleAttr) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null)

  val zms = inject[Signal[ZMessaging]]
  val isTablet = LayoutSpec.isTablet(context)
  val scaleValue = 1.4f
  val saturationValue = 2f
  val blackLevel = 0.58f

  val configuration = getResources.getConfiguration

  val screenSize = new Rect(0, 0, ViewUtils.toPx(getContext, configuration.screenWidthDp), ViewUtils.toPx(getContext, configuration.screenHeightDp))

  val pictureId: Signal[ImageSource] = for {
    z <- zms
    Some(picture) <- z.usersStorage.signal(z.selfUserId).map(_.picture)
  } yield WireImage(picture)

  private val drawable = new BlurredImageAssetDrawable(pictureId, scaleType = ScaleType.CenterXCrop, request = RequestBuilder.Single, blurRadius = 25, blurPasses = 6, context = getContext, animate = false)
  drawable.fixedBounds ! Some(screenSize)
  val matrix = new ColorMatrix

  matrix.setSaturation(saturationValue)
  drawable.setColorFilter(new ColorMatrixColorFilter(matrix))
  setBackground(drawable)
  setImageDrawable(new ColorDrawable(ColorUtils.injectAlpha(blackLevel, Color.BLACK)))
}
