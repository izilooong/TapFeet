/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.TouchProbeLog
import kotlin.math.max
import kotlin.math.min

/**
 * Live canvas for the Lab page's keyboard-surface test.
 *
 * Everything is drawn in **screen** coordinates and then letterboxed into whatever box the layout
 * hands out. That matters: the probe records `rawX/rawY` (absolute display coordinates), so a trace
 * keeps its true position relative to the display only if the canvas shares that coordinate space.
 * Scaling into the view's own box instead would silently turn the question being asked — *where does
 * this keyboard surface land on the screen* — into "what shape was that swipe".
 *
 * The IME's touchable band is drawn as a reference for the same reason: on this hardware the keyboard
 * surface resolves to the middle of the display while the input method only owns a sliver at the very
 * bottom, and seeing those two not overlap is the whole reason gestures never reach it.
 *
 * One stroke per gesture (split on DOWN → UP/CANCEL), coloured by the entry path that delivered it,
 * so "which window is receiving this?" is answered without reading a single log line.
 */
class TouchTrailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * Top edge of the input method's touchable area, in screen coordinates. Negative means the IME
     * is not showing (nothing to draw).
     */
    var imeTop: Float = -1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /**
     * Sizes itself from the display's own aspect ratio. The caller gives no width or height hint: the
     * canvas takes a fixed share of the display width and derives the height from it.
     *
     * Both dimensions have to come from here rather than the layout for two reasons. The drawing space
     * is taller than it is wide, so any layout height either crops the band or letterboxes it to a
     * stamp; and a weighted width would have LinearLayout re-measure the child with an EXACTLY spec
     * after the weight pass, overriding whatever ratio was computed.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dm = resources.displayMetrics
        val ratio = (dm.heightPixels * (1f - TOP_FRACTION)) / dm.widthPixels
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val w = min((dm.widthPixels * WIDTH_FRACTION).toInt(), available)
        setMeasuredDimension(w, (w * ratio).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        if (width <= 0 || height <= 0 || sw <= 0f || sh <= 0f) return

        // Crop the blank top band of the display before fitting. The keyboard surface starts about a
        // fifth of the way down (measured: Y 234 of 1200), so drawing the untouched top would only
        // shrink the traces to no benefit. The coordinate space stays absolute either way.
        val viewTop = sh * TOP_FRACTION
        val viewH = sh - viewTop
        // Fit with a sliver of margin. A stroke ending near the display edge — which is exactly where
        // the rightmost column lands — would otherwise be halved by the canvas boundary, and a
        // half-drawn stroke at the edge is indistinguishable from no stroke at all.
        val scale = min(width / sw, height / viewH) * FIT_MARGIN
        // Centre, but never with a negative offset. When one axis is tight the leftover on the other
        // axis has to stay on the right/bottom edge — centring with a negative value pushes content
        // off *both* ends at once, which is how the first version hid the whole IME band and the top
        // of the keyboard surface while still looking plausible.
        val offX = max(0f, (width - sw * scale) / 2f)
        val offY = max(0f, (height - viewH * scale) / 2f)

        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.translate(0f, -viewTop)

        // Widths and text sizes are authored in device pixels and divided by the scale, so they stay
        // visually constant whatever the letterbox factor turns out to be.
        val lineW = 2.5f / scale
        val textPx = 11f * dm.density / scale

        fillPaint.color = COLOR_PANEL
        canvas.drawRect(0f, 0f, sw, sh, fillPaint)

        strokePaint.pathEffect = null
        strokePaint.strokeWidth = 1f / scale
        strokePaint.color = COLOR_GRID
        var gy = GRID_STEP
        while (gy < sh) {
            canvas.drawLine(0f, gy, sw, gy, strokePaint)
            gy += GRID_STEP
        }
        var gx = GRID_STEP
        while (gx < sw) {
            canvas.drawLine(gx, 0f, gx, sh, strokePaint)
            gx += GRID_STEP
        }

        strokePaint.strokeWidth = lineW
        strokePaint.color = COLOR_BORDER
        canvas.drawRect(0f, 0f, sw, sh, strokePaint)

        // Column ruler: ten equal columns across the display width. The physical keyboard has ten key
        // columns too, so this is what turns "somewhere on the right" into "column 9, not column 10" —
        // a question that is impossible to settle by eye on a canvas this narrow.
        val colW = sw / COLUMN_COUNT
        strokePaint.pathEffect = null
        strokePaint.strokeWidth = 1.5f / scale
        strokePaint.color = COLOR_RULER
        for (i in 1 until COLUMN_COUNT) {
            canvas.drawLine(colW * i, viewTop, colW * i, sh, strokePaint)
        }
        textPaint.textSize = textPx * 0.85f
        textPaint.color = COLOR_RULER_TEXT
        for (i in 0 until COLUMN_COUNT) {
            canvas.drawText("${i + 1}", colW * i + 6f, viewTop + textPx + 8f, textPaint)
        }

        // The input method's own touchable area — the region a gesture would have to land in to be
        // seen by the IME at all.
        if (imeTop > 0f && imeTop < sh) {
            fillPaint.color = COLOR_IME_FILL
            canvas.drawRect(0f, imeTop, sw, sh, fillPaint)
            strokePaint.color = COLOR_IME_LINE
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
            canvas.drawLine(0f, imeTop, sw, imeTop, strokePaint)
            strokePaint.pathEffect = null
            textPaint.color = COLOR_IME_LINE
            textPaint.textSize = textPx
            canvas.drawText(context.getString(R.string.touch_trail_ime_band), 12f, imeTop - 10f, textPaint)
        }

        drawTraces(canvas, sw, sh, lineW, textPx)

        // Right-aligned so it does not collide with the ruler's "1" at the top-left.
        textPaint.color = COLOR_MUTED
        textPaint.textSize = textPx * 0.85f
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("screen ${sw.toInt()} x ${sh.toInt()}", sw - 12f, viewTop + textPx + 8f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        canvas.restore()
    }

    private fun drawTraces(canvas: Canvas, sw: Float, sh: Float, lineW: Float, textPx: Float) {
        // Shared with the text read-out, so the strokes drawn here are the strokes it judges.
        val strokes = buildTouchStrokes(TouchProbeLog.snapshot())
        if (strokes.isEmpty()) {
            textPaint.color = COLOR_MUTED
            textPaint.textSize = textPx
            canvas.drawText(context.getString(R.string.touch_trail_waiting), sw * 0.06f, sh * 0.5f, textPaint)
            return
        }

        strokes.forEachIndexed { index, stroke ->
            val newest = index == strokes.lastIndex
            val age = strokes.lastIndex - index
            val color = when {
                stroke.cancelled -> COLOR_CANCEL
                stroke.ongoing -> COLOR_ONGOING
                else -> pathColor(stroke.points[0].path)
            }

            tracePaint.color = color
            tracePaint.alpha = when {
                newest -> 255
                age < 3 -> 200
                age < 8 -> 140
                else -> 85
            }
            tracePaint.strokeWidth = if (newest) lineW * 1.8f else lineW * 1.2f

            path.reset()
            stroke.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.rawX, p.rawY) else path.lineTo(p.rawX, p.rawY)
            }
            canvas.drawPath(path, tracePaint)

            // Endpoints only on the newest stroke: on older ones they are just noise.
            if (!newest) return@forEachIndexed

            val first = stroke.points.first()
            val last = stroke.points.last()
            val r = lineW * 3.5f

            dotPaint.color = COLOR_ORIGIN
            dotPaint.alpha = 255
            canvas.drawCircle(first.rawX, first.rawY, r, dotPaint)

            dotPaint.color = color
            dotPaint.alpha = 255
            when {
                stroke.ongoing -> canvas.drawCircle(last.rawX, last.rawY, r * 1.4f, dotPaint)

                stroke.cancelled -> {
                    // A cross marks a gesture the framework took away mid-flight.
                    strokePaint.color = color
                    strokePaint.strokeWidth = lineW * 1.8f
                    strokePaint.pathEffect = null
                    val d = r * 1.6f
                    canvas.drawLine(
                        last.rawX - d, last.rawY - d, last.rawX + d, last.rawY + d, strokePaint
                    )
                    canvas.drawLine(
                        last.rawX - d, last.rawY + d, last.rawX + d, last.rawY - d, strokePaint
                    )
                }

                else -> canvas.drawRect(
                    last.rawX - r, last.rawY - r, last.rawX + r, last.rawY + r, dotPaint
                )
            }
        }
    }

    /**
     * Colour by *which window* delivered the gesture — the answer this probe exists to give.
     * Deliberately not by gesture index: "pointer mode goes somewhere else" then shows up as a change
     * of colour instead of a change of shade.
     */
    private fun pathColor(path: String): Int = when (path) {
        TouchProbeLog.PATH_APP_MOTION, TouchProbeLog.PATH_IME_MOTION -> COLOR_MOTION
        TouchProbeLog.PATH_IME_RECEIVER -> COLOR_RECEIVER
        else -> COLOR_TOUCH
    }

    private companion object {
        const val GRID_STEP = 200f

        /** Share of the display height that is always empty on these devices; cropped before fitting. */
        const val TOP_FRACTION = 0.15f

        /** Canvas width as a share of the display width; the height follows from the aspect ratio. */
        const val WIDTH_FRACTION = 0.40f

        /** Key columns on the physical keyboard; the ruler divides the display into this many. */
        const val COLUMN_COUNT = 10

        /** Shrink factor applied when fitting, so edge-hugging strokes are not clipped in half. */
        const val FIT_MARGIN = 0.97f

        const val COLOR_PANEL = 0xFFFBFCFE.toInt()
        const val COLOR_BORDER = 0xFFB6C0CE.toInt()
        const val COLOR_GRID = 0x1A4A5A70
        const val COLOR_MUTED = 0xFF8A94A3.toInt()

        const val COLOR_RULER = 0x44C08000
        const val COLOR_RULER_TEXT = 0xFFA86800.toInt()

        const val COLOR_IME_FILL = 0x1FE05555
        const val COLOR_IME_LINE = 0xFFCC4444.toInt()

        const val COLOR_TOUCH = 0xFF1B6EF3.toInt()
        const val COLOR_MOTION = 0xFF8B5CF6.toInt()
        const val COLOR_RECEIVER = 0xFF0E9F9F.toInt()

        const val COLOR_ONGOING = 0xFF12A150.toInt()
        const val COLOR_CANCEL = 0xFFE05555.toInt()
        const val COLOR_ORIGIN = 0xFF16305C.toInt()
    }
}
