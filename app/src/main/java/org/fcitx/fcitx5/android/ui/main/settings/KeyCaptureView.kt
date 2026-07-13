/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp

/**
 * View that captures hardware key press with virtual modifier buttons and a confirm button.
 */
class KeyCaptureView(
    context: Context,
    initialValue: Int,
    private val onConfirmed: (Int) -> Unit
) : LinearLayout(context) {

    private val hintText: TextView
    private val capturedText: TextView
    private val modifierButtonsLayout: LinearLayout
    private val confirmButton: TextView

    private val modifierButtons = mutableMapOf<Int, TextView>()
    private var virtualMetaState = 0
    private var capturedKeyCode: Int? = null
    private var currentValue: Int = initialValue

    private data class ModifierInfo(
        val metaFlag: Int,
        val keyCode: Int,
        val label: String
    )

    private val modifierList = listOf(
        ModifierInfo(KeyEvent.META_SHIFT_ON, KeyEvent.KEYCODE_SHIFT_LEFT, "Shift"),
        ModifierInfo(KeyEvent.META_ALT_ON, KeyEvent.KEYCODE_ALT_LEFT, "Alt"),
        ModifierInfo(KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl"),
        ModifierInfo(KeyEvent.META_SYM_ON, KeyEvent.KEYCODE_SYM, "Sym"),
        ModifierInfo(KeyEvent.META_FUNCTION_ON, KeyEvent.KEYCODE_FUNCTION, "Fn")
    )

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)

        hintText = TextView(context).apply {
            setText(R.string.key_capture_hint)
            this.gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#888888"))
        }
        addView(hintText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

        modifierButtonsLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        addView(modifierButtonsLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        modifierList.forEach { info ->
            val button = createModifierButton(info.label, info.metaFlag)
            modifierButtons[info.metaFlag] = button
            modifierButtonsLayout.addView(button, LinearLayout.LayoutParams(
                dp(42),
                dp(28)
            ).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }

        capturedText = TextView(context).apply {
            text = formatCurrentValue()
            this.gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#333333"))
        }
        addView(capturedText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8); topMargin = dp(2) })

        confirmButton = TextView(context).apply {
            text = "确认"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(6), dp(20), dp(6))
            background = createConfirmButtonBackground()
            setOnClickListener {
                onConfirmed(currentValue)
            }
        }
        addView(confirmButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()

        updateModifierButtons()
    }

    private fun formatCurrentValue(): String {
        if (currentValue == 0) return "(未设置)"
        val keyCode = decodeKeyCode(currentValue)
        val metaState = decodeMetaState(currentValue)
        return formatKey(keyCode, metaState)
    }

    private fun createModifierButton(label: String, metaFlag: Int): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor("#666666"))
            setPadding(dp(2), dp(1), dp(2), dp(1))
            background = createButtonBackground(false)
            setOnClickListener {
                toggleModifier(metaFlag)
            }
        }
    }

    private fun createButtonBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(if (selected) Color.parseColor("#D0D0D0") else Color.parseColor("#F5F5F5"))
            setStroke(1, if (selected) Color.parseColor("#888888") else Color.parseColor("#CCCCCC"))
        }
    }

    private fun createConfirmButtonBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#4A90D9"))
        }
    }

    private fun toggleModifier(metaFlag: Int) {
        virtualMetaState = if (virtualMetaState and metaFlag != 0) {
            virtualMetaState and metaFlag.inv()
        } else {
            virtualMetaState or metaFlag
        }
        updateModifierButtons()
        updateFromVirtualModifiers()
        requestFocus()
    }

    private fun updateModifierButtons() {
        modifierButtons.forEach { (metaFlag, button) ->
            val selected = virtualMetaState and metaFlag != 0
            button.background = createButtonBackground(selected)
            button.setTextColor(if (selected) Color.parseColor("#333333") else Color.parseColor("#666666"))
        }
    }

    private fun updateFromVirtualModifiers() {
        if (capturedKeyCode != null) {
            val combined = encodeKey(capturedKeyCode!!, virtualMetaState)
            currentValue = combined
            capturedText.text = formatKey(capturedKeyCode!!, virtualMetaState)
        } else if (virtualMetaState != 0) {
            val firstModifier = modifierList.first { virtualMetaState and it.metaFlag != 0 }
            currentValue = encodeKey(firstModifier.keyCode, virtualMetaState)
            capturedText.text = formatKey(firstModifier.keyCode, virtualMetaState)
        } else {
            capturedText.text = formatCurrentValue()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isModifierKey(keyCode)) {
            return true
        }
        val physicalMeta = normalizeMetaState(event.metaState)
        val combinedMeta = virtualMetaState or physicalMeta
        capturedKeyCode = keyCode
        currentValue = encodeKey(keyCode, combinedMeta)
        capturedText.text = formatKey(keyCode, combinedMeta)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isModifierKey(keyCode) && capturedKeyCode == null && virtualMetaState == 0) {
            val keyMeta = modifierKeyToMeta(keyCode)
            capturedKeyCode = keyCode
            currentValue = encodeKey(keyCode, keyMeta)
            virtualMetaState = keyMeta
            updateModifierButtons()
            capturedText.text = formatKey(keyCode, keyMeta)
        }
        return true
    }

    companion object {
        fun normalizeMetaState(metaState: Int): Int {
            var normalized = 0
            if (metaState and KeyEvent.META_ALT_ON != 0) normalized = normalized or KeyEvent.META_ALT_ON
            if (metaState and KeyEvent.META_SHIFT_ON != 0) normalized = normalized or KeyEvent.META_SHIFT_ON
            if (metaState and KeyEvent.META_CTRL_ON != 0) normalized = normalized or KeyEvent.META_CTRL_ON
            if (metaState and KeyEvent.META_META_ON != 0) normalized = normalized or KeyEvent.META_META_ON
            if (metaState and KeyEvent.META_SYM_ON != 0) normalized = normalized or KeyEvent.META_SYM_ON
            if (metaState and KeyEvent.META_FUNCTION_ON != 0) normalized = normalized or KeyEvent.META_FUNCTION_ON
            return normalized
        }

        fun isModifierKey(keyCode: Int): Boolean {
            return when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT,
                KeyEvent.KEYCODE_SYM,
                KeyEvent.KEYCODE_FUNCTION -> true
                else -> false
            }
        }

        fun modifierKeyToMeta(keyCode: Int): Int {
            return when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
                KeyEvent.KEYCODE_SYM -> KeyEvent.META_SYM_ON
                KeyEvent.KEYCODE_FUNCTION -> KeyEvent.META_FUNCTION_ON
                else -> 0
            }
        }

        fun encodeKey(keyCode: Int, metaState: Int): Int {
            return (metaState shl 16) or (keyCode and 0xFFFF)
        }

        fun decodeKeyCode(combined: Int): Int {
            return combined and 0xFFFF
        }

        fun decodeMetaState(combined: Int): Int {
            return (combined shr 16) and 0xFFFF
        }

        fun metaStateToString(metaState: Int): String {
            val sb = StringBuilder()
            if (metaState and KeyEvent.META_SHIFT_ON != 0) sb.append("Shift")
            if (metaState and KeyEvent.META_ALT_ON != 0) {
                if (sb.isNotEmpty()) sb.append('+')
                sb.append("Alt")
            }
            if (metaState and KeyEvent.META_CTRL_ON != 0) {
                if (sb.isNotEmpty()) sb.append('+')
                sb.append("Ctrl")
            }
            if (metaState and KeyEvent.META_META_ON != 0) {
                if (sb.isNotEmpty()) sb.append('+')
                sb.append("Meta")
            }
            if (metaState and KeyEvent.META_SYM_ON != 0) {
                if (sb.isNotEmpty()) sb.append('+')
                sb.append("Sym")
            }
            if (metaState and KeyEvent.META_FUNCTION_ON != 0) {
                if (sb.isNotEmpty()) sb.append('+')
                sb.append("Fn")
            }
            return sb.toString()
        }

        fun formatKey(keyCode: Int, metaState: Int): String {
            return buildString {
                if (metaState != 0) {
                    append(metaStateToString(metaState))
                    append("+")
                }
                append(KeyEvent.keyCodeToString(keyCode))
                append(" (")
                append(keyCode)
                append(")")
            }
        }
    }
}