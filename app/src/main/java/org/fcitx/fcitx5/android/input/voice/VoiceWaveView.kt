/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.sin

/**
 * 语音输入音浪动画条：替代工具栏麦克风按钮的图标，给「正在听 / 正在识别」一个可见反馈。
 *
 * - 录音中（indeterminate = false）：5 根圆角竖条的高度跟随 [level]（麦克风音量 0..1），
 *   内部做指数平滑，说话时起伏、安静时回落 —— 用户一眼能看出"麦克风确实在收音"；
 * - 识别中（indeterminate = true）：竖条按正弦波滚动，表示后台在处理。
 */
class VoiceWaveView(context: Context) : View(context) {

    /** 目标音量 0..1；视图内部平滑到 [smoothedLevel]。 */
    var level: Float = 0f

    /** true = 识别中（波浪滚动）；false = 录音中（跟随音量）。 */
    var indeterminate: Boolean = false

    private var smoothedLevel = 0f
    private var startTime = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    fun setBarColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startTime == 0L) startTime = System.currentTimeMillis()
        smoothedLevel += (level - smoothedLevel) * 0.25f
        val t = (System.currentTimeMillis() - startTime) / 1000f
        val barCount = 5
        val gap = width / (barCount * 2f + 1)
        val barWidth = gap
        val maxH = height * 0.85f
        val minH = height * 0.18f
        for (i in 0 until barCount) {
            val phase = i * 0.9f
            val factor = if (indeterminate) {
                0.55f + 0.45f * sin(t * 6f + phase)
            } else {
                // 各条错开一点相位，纯音量驱动会有"成排同步"的呆板感
                val skew = 0.75f + 0.25f * sin(t * 3f + phase)
                (0.12f + 0.88f * smoothedLevel) * skew
            }
            val h = (minH + (maxH - minH) * factor.coerceIn(0f, 1f)) / 2f
            val cx = gap * (i * 2 + 1.5f)
            rect.set(cx - barWidth / 2f, height / 2f - h, cx + barWidth / 2f, height / 2f + h)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
        if (visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }
}
