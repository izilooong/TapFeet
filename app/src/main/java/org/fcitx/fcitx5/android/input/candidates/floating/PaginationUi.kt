/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.styledFloat
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.imageDrawable
import splitties.views.padding

class PaginationUi(override val ctx: Context, val theme: Theme) : Ui {

    private fun createIcon(@DrawableRes icon: Int) = imageView {
        imageTintList = ColorStateList.valueOf(theme.keyTextColor)
        imageDrawable = drawable(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        padding = dp(2)
        isClickable = true
        background = borderlessRippleDrawable(theme.keyPressHighlightColor, dp(20))
    }

    val prevIcon = createIcon(R.drawable.ic_baseline_arrow_prev_24).apply {
        // Buttons are stacked vertically, so rotate the horizontal arrows:
        // prev (left arrow) points up, next (right arrow) points down.
        rotation = 90f
    }
    val nextIcon = createIcon(R.drawable.ic_baseline_arrow_next_24).apply {
        rotation = 90f
    }

    private val disabledAlpha = styledFloat(android.R.attr.disabledAlpha)

    // Vertically stacked: prev on top, next on bottom. The caller sizes this view's root to the
    // default candidate row height, and the layout weights split it evenly between the two
    // buttons (each gets half a row).
    override val root = verticalLayout {
        add(prevIcon, lParams(matchParent, 0, weight = 1f))
        add(nextIcon, lParams(matchParent, 0, weight = 1f))
    }

    init {
        // Each button is only half a row tall, which is too small for comfortable touch targets.
        // Enlarge the effective clickable area beyond the visual bounds via touch delegates.
        val extra = ctx.dp(12)
        root.post {
            val composite = TouchDelegateComposite(Rect(), root)
            listOf(prevIcon, nextIcon).forEach { icon ->
                val rect = Rect()
                icon.getHitRect(rect)
                rect.inset(-extra, -extra)
                composite.addDelegate(TouchDelegate(rect, icon))
            }
            root.touchDelegate = composite
        }
    }

    /**
     * [TouchDelegate] only forwards to a single child per parent; both paging buttons need
     * enlarged hit rects, so aggregate them into one delegate.
     */
    private class TouchDelegateComposite(rect: Rect, view: View) :
        TouchDelegate(rect, view) {
        private val delegates = mutableListOf<TouchDelegate>()

        fun addDelegate(delegate: TouchDelegate) {
            delegates.add(delegate)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean =
            delegates.any { it.onTouchEvent(event) }
    }

    fun update(data: FcitxEvent.PagedCandidateEvent.Data) {
        prevIcon.alpha = if (data.hasPrev) 1f else disabledAlpha
        nextIcon.alpha = if (data.hasNext) 1f else disabledAlpha
    }
}
