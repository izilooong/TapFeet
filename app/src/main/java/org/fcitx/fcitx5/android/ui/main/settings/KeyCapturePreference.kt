/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R

/**
 * Preference that captures a hardware keyboard key binding using the fcitx5 Key system.
 * Stores the key as a fcitx5 portableString (e.g. "Alt+space", "dollar", "Shift_L"),
 * or the name of a [HardwareSpecialKeys] pseudo key (e.g. "Sym", "NavBack") for physical function
 * keys that have no fcitx5 KeySym.
 * Uses [KeyCaptureUi] (same approach as the global [FcitxKeyPreference]).
 */
class KeyCapturePreference : Preference {

    private var defaultKeyValue: String = ""

    /** The configured default value, used as the SharedPreferences fallback in the summary. */
    internal val defaultValue: String get() = defaultKeyValue

    /**
     * 可选的冲突提示钩子。传入用户刚捕获的键字符串，返回非 null 表示"这个键已经被别的绑定占用了"，
     * 返回值就是展示给用户的说明文字（含占用方名字）。
     *
     * 返回非 null 时**不直接落盘**，而是先弹一个二次确认，用户可以选择覆盖 —— 不做硬拦截：
     * 冲突未必是错误（用户可能就是想改绑），挡死反而更烦。
     *
     * 目前只有「快捷键」页会设置它；其他调用点保持 null，行为与从前完全一致。
     */
    var conflictHint: ((String) -> CharSequence?)? = null

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) :
            this(context, attrs, androidx.preference.R.attr.preferenceStyle)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? {
        defaultKeyValue = a.getString(index) ?: ""
        return defaultKeyValue
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        if (defaultValue is String) {
            defaultKeyValue = defaultValue
        }
    }

    override fun onClick() {
        showDialog()
    }

    private fun showDialog() {
        val currentValue = sharedPreferences?.getString(key, defaultKeyValue) ?: defaultKeyValue
        val ui = KeyCaptureUi(context, currentValue)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ui.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = ui.getValue()
                val conflict = conflictHint?.invoke(value)
                if (conflict == null) persistIfChanged(value)
                else showConflictConfirm(value, conflict)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.hw_reset) { _, _ ->
                persistIfChanged(defaultKeyValue)
            }
            .show()
    }

    private fun persistIfChanged(value: String) {
        if (callChangeListener(value)) {
            persistString(value)
            notifyChanged()
        }
    }

    /**
     * 冲突二次确认。AlertDialog 的按钮点击会先关掉原对话框，所以这里另开一个而不是原地改文案 ——
     * 代价是多一次弹窗，好处是完全不碰 [KeyCaptureUi]（共享组件，别的页面也在用）。
     */
    private fun showConflictConfirm(value: String, conflict: CharSequence) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(conflict)
            .setPositiveButton(android.R.string.ok) { _, _ -> persistIfChanged(value) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    object KeySummaryProvider : SummaryProvider<KeyCapturePreference> {
        override fun provideSummary(preference: KeyCapturePreference): CharSequence {
            val v = preference.sharedPreferences?.getString(preference.key, preference.defaultValue)
                ?: preference.defaultValue
            if (v.isEmpty()) return preference.context.getString(R.string.none)
            return KeyCaptureUi.formatKey(preference.context, v)
        }
    }

    /** Refresh the summary after the underlying value was changed externally (e.g. by a preset). */
    fun refresh() = notifyChanged()
}
