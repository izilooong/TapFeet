/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.KeyCapturePreference

class HardwareKeyboardSettingsFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val hw = AppPrefs.getInstance().hardwareKeyboard
        listOf(
            hw.candidate1Key to R.string.candidate_key_1,
            hw.candidate2Key to R.string.candidate_key_2,
            hw.candidate3Key to R.string.candidate_key_3,
            hw.candidate4Key to R.string.candidate_key_4,
            hw.candidate5Key to R.string.candidate_key_5,
            hw.pageNextKey to R.string.candidate_page_next,
            hw.pagePrevKey to R.string.candidate_page_prev,
            hw.symbolPickerKey to R.string.hw_symbol_picker,
        ).forEach { (pref, titleRes) ->
            val capture = KeyCapturePreference(context).apply {
                key = pref.key
                title = getString(titleRes)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setDefaultValue(pref.getValue())
                summaryProvider = KeyCapturePreference.KeySummaryProvider
            }
            screen.addPreference(capture)
        }

        preferenceScreen = screen
    }
}