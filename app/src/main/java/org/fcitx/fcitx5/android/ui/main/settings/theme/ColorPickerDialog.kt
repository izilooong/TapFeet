/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

/**
 * Saturation/Value panel. Renders a 2D gradient for the current [hue] and reports
 * touch events as saturation (x) and value/brightness (y).
 */
class SVPanelView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    var hue = 0f
        set(value) {
            field = value
            rebuildBitmap()
            invalidate()
        }

    var sat = 0f // 0f..1f
        private set
    var value = 1f // 0f..1f (brightness)
        private set
    var onChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null

    private fun rebuildBitmap() {
        if (width == 0 || height == 0) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        bitmap?.recycle()
        bitmap = bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val x = sat * width
        val y = (1f - value) * height
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        canvas.drawCircle(x, y, 10f, paint)
        paint.color = Color.BLACK
        paint.strokeWidth = 2f
        canvas.drawCircle(x, y, 10f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            sat = (event.x / width).coerceIn(0f, 1f)
            value = (1f - event.y / height).coerceIn(0f, 1f)
            invalidate()
            onChanged?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    fun setSV(s: Float, v: Float) {
        sat = s
        value = v
        invalidate()
    }
}

/**
 * Lightweight HSV color picker dialog with no third-party dependency.
 * Emits the picked color as a 0xAARRGGBB [Int] via [onPicked].
 */
class ColorPickerDialog(
    context: Context,
    initialColor: Int,
    private val onPicked: (Int) -> Unit
) {
    private val hsv = FloatArray(3)
    private var currentAlpha: Int = 255

    private var svPanel: SVPanelView
    private var hueSeek: SeekBar
    private var alphaSeek: SeekBar
    private var preview: View
    private var hexText: TextView

    private val dialog: AlertDialog

    init {
        Color.colorToHSV(initialColor, hsv)
        currentAlpha = initialColor.ushr(24).coerceIn(0, 255)

        val dp = { v: Int -> (v * context.resources.displayMetrics.density).roundToInt() }

        svPanel = SVPanelView(context).apply {
            // Fixed height instead of weight (0,1f): AlertDialog measures its content
            // view as wrap-content, so a weight-based height collapses to 0 and the
            // SV panel becomes invisible/undraggable. 200dp keeps it always usable.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)
            ).apply { setMargins(0, 0, 0, dp(12)) }
            hue = hsv[0]
            setSV(hsv[1], hsv[2])
            onChanged = {
                // Mirror the SV panel's saturation/value back into the shared hsv array.
                // Without this, currentColor() keeps returning the *initial* sat/value and
                // dragging the panel has no effect on the picked color.
                hsv[1] = svPanel.sat
                hsv[2] = svPanel.value
                updatePreview()
            }
        }

        hueSeek = SeekBar(context).apply {
            max = 360
            progress = hsv[0].roundToInt()
            background = rainbowDrawable()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    hsv[0] = p.toFloat()
                    svPanel.hue = p.toFloat()
                    updatePreview()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        alphaSeek = SeekBar(context).apply {
            max = 255
            progress = currentAlpha
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    currentAlpha = p
                    updatePreview()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        hexText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
        }
        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(preview)
            addView(hexText)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(svPanel)
            addView(hueSeek)
            addView(alphaSeek)
            addView(previewRow)
        }

        updatePreview()

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(currentColor()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun currentColor(): Int = Color.HSVToColor(currentAlpha, hsv)

    private fun updatePreview() {
        val c = currentColor()
        preview.setBackgroundColor(c)
        hexText.text = String.format("#%08X", c)
    }

    private fun rainbowDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        )
    )

    fun show() = dialog.show()
}
