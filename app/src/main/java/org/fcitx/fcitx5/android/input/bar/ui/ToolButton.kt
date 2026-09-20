/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation
    }

    val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        image.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        setIcon(icon)
        setPressHighlightColor(theme.keyPressHighlightColor)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun setIcon(@DrawableRes icon: Int) {
        // 部分 ROM（实测 Unihertz Titan 2 Elite）的 ImageView.applyImageTint 在「已设 imageTintList
        // 但 drawable 解析为 null」时不判空直接 mutate() 而 NPE。这里先自行解析 drawable，解析不到
        // 就回退到通用键盘图标，再不行才跳过，绝不直接把可能解析失败的 resource id 喂给
        // setImageResource（其 getDrawable 在该 ROM 上对复杂字形矢量 ic_status_* 会返回 null，
        // 进而 mDrawable 为空、mHasTint 为真 → 触发框架 NPE）。
        val d = resolve(icon) ?: resolve(R.drawable.ic_baseline_keyboard_24)
        if (d != null) {
            image.setImageDrawable(d)
        } else {
            Timber.w("ToolButton.setIcon: drawable 0x%x 解析失败，跳过以避免 ImageView NPE", icon)
        }
    }

    private fun resolve(@DrawableRes icon: Int) =
        runCatching { AppCompatResources.getDrawable(image.context, icon) }.getOrNull()

    fun setPressHighlightColor(@ColorInt color: Int) {
        background = if (disableAnimation) {
            circlePressHighlightDrawable(color)
        } else {
            borderlessRippleDrawable(color, dp(20))
        }
    }
}
