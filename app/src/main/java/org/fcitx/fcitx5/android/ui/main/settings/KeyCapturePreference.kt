/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R

/**
 * Preference that captures Android KeyEvent keyCode.
 * User taps the preference to open a capture dialog, presses a key, and the keyCode is saved.
 */
class KeyCapturePreference : Preference {

    private var defaultKeyCode: Int = 0

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) :
            this(context, attrs, androidx.preference.R.attr.preferenceStyle)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? {
        defaultKeyCode = a.getInt(index, 0)
        return defaultKeyCode
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        if (defaultValue is Int) {
            defaultKeyCode = defaultValue
        }
    }

    override fun onClick() {
        showDialog()
    }

    private fun showDialog() {
        val currentValue = sharedPreferences?.getInt(key, defaultKeyCode) ?: defaultKeyCode
        var dialog: AlertDialog? = null
        val captureView = KeyCaptureView(context, currentValue) { confirmedValue ->
            if (callChangeListener(confirmedValue)) {
                persistInt(confirmedValue)
                notifyChanged()
            }
            dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(captureView)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.hw_reset) { _, _ ->
                if (callChangeListener(defaultKeyCode)) {
                    persistInt(defaultKeyCode)
                    notifyChanged()
                }
            }
            .show()
    }

    object KeySummaryProvider : SummaryProvider<KeyCapturePreference> {
        override fun provideSummary(preference: KeyCapturePreference): CharSequence {
            val v = preference.sharedPreferences?.getInt(preference.key, 0) ?: 0
            if (v == 0) return "(none)"
            val keyCode = KeyCaptureView.decodeKeyCode(v)
            val metaState = KeyCaptureView.decodeMetaState(v)
            return KeyCaptureView.formatKey(keyCode, metaState)
        }
    }
}