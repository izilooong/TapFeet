/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.CustomKeyConfig
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import androidx.core.view.updateLayoutParams

/** 10 个自定义键平分整行宽度，总和 = 1.0f */
private const val CUSTOM_KEY_WIDTH = 0.1f

/**
 * 「自定义一行键盘」：一行 10 个可配置键（高度只占一行，见 [KeyboardWindow] 的单行高度切换）。
 *
 * 每个键的字符列表来自 [AppPrefs.customKeyboard]（第一项 = 单击输入的默认字符，
 * 其余项长按弹出选择）。实例在 [KeyboardWindow.switchLayout] 切换到本键盘时按需重建，
 * 因此配置保存后下次切换即生效。
 *
 * 返回主键盘靠**快速下滑手势**：按住后 300ms 内下滑超过 24dp 即触发；长按弹字符选择（按住停留
 * 超过阈值）不受影响。
 */
@SuppressLint("ViewConstructor")
class CustomKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout(AppPrefs.getInstance().customKeyboard.keys.getValue())) {

    private val swipeBackDistancePx = dp(24)
    private val swipeBackTimeThresholdMs = 300L
    private var gestureDownY = 0f
    private var gestureDownTime = 0L

    init {
        // 单行键盘：唯一一行必须充满整个键盘窗口高度。否则 wrap_content 行在 ConstraintLayout
        // 中会因内部 matchParent 子（appearanceView）无法撑开而塌缩为 0，导致所有键不可见。
        (getChildAt(0) as? ConstraintLayout)?.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = 0 // MATCH_CONSTRAINT，配合行的 topOfParent/bottomOfParent 撑满父高
        }
    }

    companion object {
        const val Name = "Custom"

        /** 一行 10 个自定义键（各占 [CUSTOM_KEY_WIDTH]），高度由 [KeyboardWindow] 压到单行 */
        fun Layout(keys: List<CustomKeyConfig>): List<List<KeyDef>> =
            listOf(keys.take(10).map(::CustomKey))

        /**
         * 单码点走 fcitx sendKey（字符上屏铁律）；多码点字符串无法用单个 key 表达，
         * 走 [KeyAction.CommitAction]（项目约定的例外场景）。
         */
        fun actionFor(text: String): KeyAction =
            if (text.codePointCount(0, text.length) == 1) KeyAction.FcitxKeyAction(text)
            else KeyAction.CommitAction(text)
    }

    /**
     * 拦截"快速下滑"手势切回主键盘：按住 300ms 内下滑超过 24dp 才拦截；
     * 长按（停留超时）或轻微滑动放行给键（长按弹字符选择不受影响）。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownY = ev.y
                gestureDownTime = ev.eventTime
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.eventTime - gestureDownTime < swipeBackTimeThresholdMs &&
                    ev.y - gestureDownY > swipeBackDistancePx
                ) {
                    onAction(KeyAction.LayoutSwitchAction(TextKeyboard.Name))
                    return true
                }
                return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}

/**
 * 单个自定义键：键帽与主键盘数字键一致——大字显示主字符（[CustomKeyConfig.chars] 首项，
 * 即单击默认字符），右上角小字显示次字符（第二项）；单击输入主字符，长按弹出**除主字符外的
 * 其余字符**（次字符及其后的字符），即长按选择列表不重复出现主字符。
 *
 * 仅有一项时退化为普通 [Appearance.Text]（无次字符小字）；仅一项时长按无候选可弹，仅保留按压预览。
 */
class CustomKey(config: CustomKeyConfig) : KeyDef(
    appearance = run {
        val chars = config.chars.filter { it.isNotBlank() }
        val main = chars.firstOrNull() ?: ""
        val alt = chars.getOrNull(1) ?: ""
        if (alt.isNotBlank())
            Appearance.AltText(
                displayText = main,
                altText = alt,
                textSize = 16f,
                percentWidth = CUSTOM_KEY_WIDTH,
                variant = Appearance.Variant.Alternative
            )
        else
            Appearance.Text(
                displayText = main,
                textSize = 16f,
                percentWidth = CUSTOM_KEY_WIDTH,
                variant = Appearance.Variant.Alternative
            )
    },
    behaviors = config.chars.filter { it.isNotBlank() }.firstOrNull()
        ?.let { setOf(Behavior.Press(CustomKeyboard.actionFor(it))) } ?: emptySet(),
    popup = config.chars.filter { it.isNotBlank() }.let { chars ->
        if (chars.isEmpty()) null
        else {
            val popups = mutableListOf<Popup>(Popup.Preview(chars.first()))
            chars.drop(1).takeIf { it.isNotEmpty() }
                ?.let { popups.add(Popup.Keyboard.Explicit(it.toTypedArray())) }
            popups.toTypedArray()
        }
    }
)
