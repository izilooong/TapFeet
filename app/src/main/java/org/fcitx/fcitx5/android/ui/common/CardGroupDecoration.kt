/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R

/**
 * 设置页卡片背景工具(不再是 ItemDecoration)。
 *
 * 设计:设置项数量不多、且不需要回收——view 一旦创建,背景就"定死"在它身上,滚动只做
 * detach/attach,background 永远跟着 view 走,不存在 RecyclerView 复用导致的"背景丢失"。
 * 因此背景在 view 首次 attach 时算好圆角设一次即可,**不再每帧 canvas 动态重画**。
 *
 * 调用方(如 [org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment])需把 RecyclerView
 * 的 scrap cache 开大(setItemViewCacheSize),避免 view 被回收进 pool;再在
 * OnChildAttachStateChangeListener 里对首次 attach 的 view 调 [shapeFor],把背景设死。
 */
object CardGroupDecoration {

    /** Horizontal margin of the cards, in dp. Must match the RecyclerView padding. */
    const val CARD_MARGIN_X_DP = 16

    /** Clearance above the first card, in dp, so a headerless group sitting at the list top
     * never starts flush against the screen edge. Only visible at rest: on scroll
     * clipToPadding=false lets cards rise to the edge. */
    const val CARD_TOP_PAD_DP = 8

    /** Empty space below the last card, in dp, so its rounded corners are never cut off by
     * the list bottom edge; the nav-bar inset is added on top of this. */
    const val CARD_BOTTOM_PAD_DP = 20

    private const val RADIUS_DP = 14

    /**
     * 为某一行生成它**专属**的圆角卡片 drawable(每次 new,绝不共享实例——共享会串改 bounds)。
     * [isFirst]/[isLast] 表示该行是否所在组的首/尾,决定哪几条边带圆角。
     */
    fun shapeFor(context: Context, isFirst: Boolean, isLast: Boolean): GradientDrawable {
        val r = RADIUS_DP * context.resources.displayMetrics.density
        val radii = floatArrayOf(
            if (isFirst) r else 0f, if (isFirst) r else 0f,
            if (isFirst) r else 0f, if (isFirst) r else 0f,
            if (isLast) r else 0f, if (isLast) r else 0f,
            if (isLast) r else 0f, if (isLast) r else 0f
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(context, R.color.card_bg))
            cornerRadii = radii
        }
    }

    /** 按压高亮色(随主题),作 RippleDrawable 的颜色,其 mask 用 [shapeFor] 提供的形状裁剪。 */
    fun rippleColor(context: Context): ColorStateList {
        val h = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorControlHighlight, h, true)
        return ColorStateList.valueOf(h.data)
    }
}
