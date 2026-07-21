/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 Fcitx5 for Android Contributors
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

    /**
     * References to the candidate2-5 [KeyCapturePreference] views. Their visibility is driven by
     * the bottom-row quick-pick toggle ([hw.enableCandidateQuickPick]): visible when quick-pick is
     * on (the bottom-row physical keys act as quick-pick shortcuts), hidden when it is off (the
     * keys are cleared so they don't fire on stray presses).
     */
    private val candidateShortcutPrefs = mutableListOf<KeyCapturePreference>()

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

        // 底排物理键快速选字开关：仅控制"物理键是否选词"，与候选栏排列顺序无关
        // （排列顺序在"候选栏选项 → Candidate arrangement"中设置）。
        val quickPickSwitch = SwitchPreference(context).apply {
            key = hw.enableCandidateQuickPick.key
            title = getString(R.string.hw_enable_candidate_quick_pick)
            summary = getString(R.string.hw_enable_candidate_quick_pick_summary)
            setDefaultValue(hw.enableCandidateQuickPick.getValue())
            isChecked = hw.enableCandidateQuickPick.getValue()
            isIconSpaceReserved = false
        }
        quickPickSwitch.setOnPreferenceChangeListener { _, newValue ->
            applyQuickPick(newValue as Boolean)
            true
        }
        screen.addPreference(quickPickSwitch)

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

        // Build the per-key preferences. candidate2-5 are remembered separately so the display-mode
        // handler can flip their visibility without disturbing candidate1 (first-pick, always shown).
        // Use key string (not `===` reference) to identify the four shortcut prefs — this avoids any
        // generic-vs-concrete type mismatches that can hide the list when listOf infers a wider type
        // for the iterated `pref`.
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
            if (pref.key == hw.candidate2Key.key || pref.key == hw.candidate3Key.key ||
                pref.key == hw.candidate4Key.key || pref.key == hw.candidate5Key.key) {
                candidateShortcutPrefs.add(capture)
            }
        }

        // Apply the persisted quick-pick state's visibility to candidate2-5 BEFORE returning, so
        // the screen never briefly shows rows that the current state says should be hidden.
        setCandidateShortcutVisibility(hw.enableCandidateQuickPick.getValue())

        preferenceScreen = screen
    }

    /** Override all individual key bindings with the selected preset, then refresh the summaries. */
    private fun applyProfile(name: String) {
        HardwareKeyProfiles.applyProfile(name, hw)
        keyPrefs.forEach { it.refresh() }
        setCandidateShortcutVisibility(hw.enableCandidateQuickPick.getValue())
    }

    /**
     * Enable/disable the bottom-row physical-key quick-pick:
     * - enabled  → re-seed candidate2-5 from the currently selected [hw.keyProfile] (BlackBerry
     *   or TT2) and show the four preference rows.
     * - disabled → clear candidate2-5 so the bottom-row physical keys no longer pick candidates
     *   at runtime, and hide the four preference rows.
     *
     * (candidate1 / Space still selects the first-pick word regardless of this toggle — the toggle
     * only governs the four extra candidate2-5 shortcuts, not the arrangement order.)
     */
    private fun applyQuickPick(enabled: Boolean) {
        if (enabled) {
            HardwareKeyProfiles.applyCandidateKeys(hw.keyProfile.getValue(), hw)
        } else {
            HardwareKeyProfiles.clearCandidateKeys(hw)
        }
        setCandidateShortcutVisibility(enabled)
        keyPrefs.forEach { it.refresh() }
    }

    private fun setCandidateShortcutVisibility(visible: Boolean) {
        candidateShortcutPrefs.forEach { it.isVisible = visible }
        // androidx preference 1.2.1: Preference.setVisible fires OnPreferenceChangeInternalListener
        // .onPreferenceVisibilityChange → onPreferenceHierarchyChange, which rebuilds the
        // adapter's visible-preferences list and calls notifyDataSetChanged. So setting
        // isVisible is enough — no extra notifyChanged() is needed (and that method is
        // package-private anyway, not callable from the fragment).
    }
}
