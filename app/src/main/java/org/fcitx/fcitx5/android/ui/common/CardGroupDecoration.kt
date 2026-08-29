/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R

/**
 * Groups settings rows into rounded cards, one card per [PreferenceCategory] section —
 * the iOS / WeChat settings look.
 *
 * Each visible row is given its own card background drawable: the first row of a group
 * rounds its top corners, the last row rounds its bottom corners, middle rows stay
 * square, and a [PreferenceCategory] header never gets a background — so a section title
 * can never sit on a card surface. Backgrounds travel with their views and are drawn by
 * the View system itself, so there is nothing to re-paint per frame while scrolling and
 * no ghosting or flicker is possible.
 *
 * Corners on a side that scrolled past the list edge flatten (that row's edge left the
 * screen), so a card touching the screen top goes square instead of showing rounded
 * corners stuck to the edge. A shared ripple foreground keeps the press feedback, clipped
 * to the rounded shape. The horizontal card margin must match the RecyclerView padding
 * (see [CARD_MARGIN_X_DP]) so a pressed row's ripple never spills past the corners.
 */
class CardGroupDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val radius = 14f * density

    private val bgAll: GradientDrawable
    private val bgTop: GradientDrawable
    private val bgBottom: GradientDrawable
    private val bgNone: GradientDrawable

    private val rippleColor: ColorStateList

    init {
        val cardColor = ContextCompat.getColor(context, R.color.card_bg)
        val r = radius
        fun shape(vararg radii: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardColor)
            cornerRadii = radii
        }
        // 8 radii: top-left, top-right, bottom-right, bottom-left (x & y per corner).
        bgAll = shape(r, r, r, r, r, r, r, r)
        bgTop = shape(r, r, r, r, 0f, 0f, 0f, 0f)
        bgBottom = shape(0f, 0f, 0f, 0f, r, r, r, r)
        bgNone = shape(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        // Press feedback is a per-row RippleDrawable (shared instances would leak the
        // hotspot between rows) whose mask is that row's own background, so the ripple can
        // never spill past the rounded corners of the card.
        val highlight = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorControlHighlight, highlight, true)
        rippleColor = ColorStateList.valueOf(highlight.data)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val listTop = 0
        val listBottom = parent.height
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) ?: continue
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            // Headers never get a background — only rows are painted, so a section title
            // can never sit on a card surface, on screen or mid-scroll.
            if (adapter.getItem(pos) is PreferenceCategory) continue

            // First/last row of its group carry the rounded corners. A side keeps its
            // rounding while it is genuinely on screen — the row's own edge has not scrolled
            // past the list edge. A headerless first group sitting at the list top (scroll
            // offset 0, row top at the top padding) therefore stays fully rounded, while a
            // row scrolled past the edge goes square on that side.
            val isFirstInGroup = pos == 0 || adapter.getItem(pos - 1) is PreferenceCategory
            val isLastInGroup =
                pos == adapter.itemCount - 1 || adapter.getItem(pos + 1) is PreferenceCategory
            val roundTop = isFirstInGroup && child.top >= listTop
            val roundBottom = isLastInGroup && child.bottom <= listBottom

            val bg = when {
                roundTop && roundBottom -> bgAll
                roundTop -> bgTop
                roundBottom -> bgBottom
                else -> bgNone
            }
            // Idempotent: recycled rows get corrected, steady rows are left untouched, so
            // scrolling never churns drawables. The press ripple is per-row (shared instances
            // would leak the hotspot between rows) and its mask follows the background shape,
            // so the ripple stays inside the rounded corners.
            if (child.background !== bg) {
                child.background = bg
                child.foreground = RippleDrawable(rippleColor, null, bg)
            } else if (child.foreground !is RippleDrawable) {
                child.foreground = RippleDrawable(rippleColor, null, bg)
            }
        }
    }

    companion object {
        /** Horizontal margin of the cards, in dp. Must match the RecyclerView padding. */
        const val CARD_MARGIN_X_DP = 16

        /** Clearance above the first card, in dp, so a headerless group sitting at the list
         * top never starts flush against the screen edge. Only visible at rest: on scroll
         * clipToPadding=false lets cards rise to the edge. */
        const val CARD_TOP_PAD_DP = 8

        /** Empty space below the last card, in dp, so its rounded corners are never cut off
         * by the list bottom edge; the nav-bar inset is added on top of this. */
        const val CARD_BOTTOM_PAD_DP = 20
    }
}
