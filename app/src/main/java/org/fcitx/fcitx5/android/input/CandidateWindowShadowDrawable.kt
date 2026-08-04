/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Self-contained rounded-rectangle drop shadow used by the floating candidate window.
 *
 * Why not rely on [android.view.View.setElevation]? Elevation shadows are composited by the
 * window and behave inconsistently inside an InputMethodService window (often clipped by the
 * parent, and the tinted [android.view.View.outlineSpotShadowColor] is only honoured on
 * API 28+). We paint the shadow ourselves so it renders the same on every API level and theme,
 * with a size fully controlled by [shadowRadius].
 *
 * The fill is inset by [shadowRadius] (+ vertical offset) on every side so the blurred halo has
 * room to spread inside the drawable bounds instead of being clipped away.
 */
class CandidateWindowShadowDrawable(
    private val fillColor: Int,
    private val radius: Float,
    private val shadowColor: Int,
    private val shadowRadius: Float,
    private val shadowDy: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        // setShadowLayer is honoured on a software layer (see CandidatesView.setLayerType),
        // which we force so the shadow renders reliably across all Android versions.
        setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
    }

    override fun draw(canvas: Canvas) {
        val insetX = shadowRadius
        val insetTop = shadowRadius
        val insetBottom = shadowRadius + shadowDy
        val rect = RectF(
            bounds.left + insetX,
            bounds.top + insetTop,
            bounds.right - insetX,
            bounds.bottom - insetBottom
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
