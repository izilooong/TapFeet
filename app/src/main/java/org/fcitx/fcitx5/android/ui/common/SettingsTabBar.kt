/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import org.fcitx.fcitx5.android.R

/**
 * A settings-page tab bar styled like the card groups below it: a rounded card surface
 * in [R.color.card_bg] floating on the deeper page background, with tabs laid out
 * left-aligned (not centred) — the single place that defines the look for every tabbed
 * settings page, so they can't drift apart.
 *
 * Colors come from explicit [R.color.tab_text_default] / [R.color.tab_text_selected]
 * resources, never from theme-attr resolves: `textColorSecondary` is a ColorStateList in
 * most themes, so `TypedValue.data` would be a resource id rather than a color and
 * `setTabTextColors` would draw garbage. The background tint is cleared for the same
 * reason — a Material theme's default `backgroundTint` would recolor the card surface.
 */
fun createSettingsTabBar(context: Context): TabLayout {
    val density = context.resources.displayMetrics.density
    val radius = 14f * density
    val pad = (CardGroupDecoration.CARD_MARGIN_X_DP * density).toInt()
    val topPad = (CardGroupDecoration.CARD_TOP_PAD_DP * density).toInt()

    return TabLayout(context).apply {
        // SCROLLABLE lays tabs at their natural content width from the leading edge, which
        // is guaranteed left-aligned regardless of tab count (MODE_AUTO falls back to
        // FIXED+fill behaviour for few tabs, and GRAVITY_START is unreliable there).
        tabMode = TabLayout.MODE_SCROLLABLE
        elevation = 0f
        backgroundTintList = null
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(context, R.color.card_bg))
            cornerRadii = floatArrayOf(
                radius, radius, radius, radius,
                radius, radius, radius, radius
            )
        }
        setTabTextColors(
            ContextCompat.getColor(context, R.color.tab_text_default),
            ContextCompat.getColor(context, R.color.tab_text_selected)
        )
        setSelectedTabIndicatorColor(
            ContextCompat.getColor(context, R.color.tab_text_selected)
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(pad, topPad, pad, 0)
        }
    }
}
