/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.KeyCapturePreference

class HardwareKeyboardSettingsFragment : PaddingPreferenceFragment() {

    private lateinit var hw: AppPrefs.HardwareKeyboard
    private val keyPrefs = mutableListOf<KeyCapturePreference>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        hw = AppPrefs.getInstance().hardwareKeyboard

        // On a fresh install, persist the default (BlackBerry) profile so the individual key
        // bindings match what selecting that preset would produce. No-op once already initialised.
        hw.ensureInitialized()

        // Preset profile dropdown: choosing a profile overrides every individual key binding.
        val profileList = ListPreference(context).apply {
            key = hw.keyProfile.key
            title = getString(R.string.hw_key_profile)
            entries = arrayOf(
                getString(R.string.hw_profile_blackberry),
                getString(R.string.hw_profile_tt2)
            )
            entryValues = arrayOf(HardwareKeyProfiles.BLACKBERRY, HardwareKeyProfiles.TT2)
            setDefaultValue(hw.keyProfile.getValue())
            value = hw.keyProfile.getValue()
            summary = "%s"
            isIconSpaceReserved = false
        }
        profileList.setOnPreferenceChangeListener { _, newValue ->
            applyProfile(newValue as String)
            true
        }
        screen.addPreference(profileList)

        // Master toggle: double-tap left Alt to latch the Alt modifier.
        val altLatchSwitch = SwitchPreference(context).apply {
            key = hw.altLatchEnabled.key
            title = getString(R.string.hw_alt_latch)
            summary = getString(R.string.hw_alt_latch_summary)
            setDefaultValue(hw.altLatchEnabled.getValue())
            isChecked = hw.altLatchEnabled.getValue()
            isIconSpaceReserved = false
        }
        screen.addPreference(altLatchSwitch)

        // Which key double-tap latches Alt (only relevant while the master toggle is on).
        // NOTE: we cannot use Preference.dependency here — when the screen is built dynamically
        // in onCreatePreferences, the dependency lookup runs before the manager's screen tree is
        // wired up and throws IllegalStateException. Drive enable/disable manually instead.
        val altLatchKeyPref = KeyCapturePreference(context).apply {
            key = hw.altLatchKey.key
            title = getString(R.string.hw_alt_latch_key)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(hw.altLatchKey.getValue())
            summaryProvider = KeyCapturePreference.KeySummaryProvider
        }
        altLatchKeyPref.isEnabled = altLatchSwitch.isChecked
        altLatchSwitch.setOnPreferenceChangeListener { _, newValue ->
            altLatchKeyPref.isEnabled = newValue as Boolean
            true
        }
        screen.addPreference(altLatchKeyPref)
        keyPrefs.add(altLatchKeyPref)

        listOf(
            hw.candidate1Key to R.string.candidate_key_1,
            hw.candidate2Key to R.string.candidate_key_2,
            hw.candidate3Key to R.string.candidate_key_3,
            hw.candidate4Key to R.string.candidate_key_4,
            hw.candidate5Key to R.string.candidate_key_5,
            hw.pageNextKey to R.string.candidate_page_next,
            hw.pagePrevKey to R.string.candidate_page_prev,
            hw.symbolPickerKey to R.string.hw_symbol_picker,
            hw.toggleImeKey to R.string.hw_toggle_ime,
            hw.pickerKey to R.string.hw_show_picker,
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
            keyPrefs.add(capture)
        }

        preferenceScreen = screen
    }

    /** Override all individual key bindings with the selected preset, then refresh the summaries. */
    private fun applyProfile(name: String) {
        HardwareKeyProfiles.applyProfile(name, hw)
        keyPrefs.forEach { it.refresh() }
    }
}
