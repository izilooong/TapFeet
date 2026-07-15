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
 * or the special string "Sym" for the BlackBerry SYM key.
 * Uses [KeyCaptureUi] (same approach as the global [FcitxKeyPreference]).
 */
class KeyCapturePreference : Preference {

    private var defaultKeyValue: String = ""

    /** The configured default value, used as the SharedPreferences fallback in the summary. */
    internal val defaultValue: String get() = defaultKeyValue

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
                if (callChangeListener(value)) {
                    persistString(value)
                    notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.hw_reset) { _, _ ->
                if (callChangeListener(defaultKeyValue)) {
                    persistString(defaultKeyValue)
                    notifyChanged()
                }
            }
            .show()
    }

    object KeySummaryProvider : SummaryProvider<KeyCapturePreference> {
        override fun provideSummary(preference: KeyCapturePreference): CharSequence {
            val v = preference.sharedPreferences?.getString(preference.key, preference.defaultValue)
                ?: preference.defaultValue
            if (v.isEmpty()) return preference.context.getString(R.string.none)
            return KeyCaptureUi.formatKey(v)
        }
    }

    /** Refresh the summary after the underlying value was changed externally (e.g. by a preset). */
    fun refresh() = notifyChanged()
}
