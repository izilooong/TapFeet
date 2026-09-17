/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

/**
 * Predefined sets ("profiles") of hardware keyboard key bindings.
 *
 * Each profile maps a [AppPrefs.HardwareKeyboard] key-binding preference key to its fcitx5
 * portableString value (e.g. "Alt+space", "dollar", "Shift_L") or to a [HardwareSpecialKeys]
 * pseudo-key name (e.g. "Sym", "NavBack") for physical function keys that have no fcitx5 KeySym.
 * Selecting a profile in the settings screen overwrites every individual key binding with the
 * profile's values.
 *
 * [BLACKBERRY] is the original/default set of bindings. [TT2] and [TITAN2_ELITE] are alternative
 * layouts.
 * The individual keys remain editable afterwards, so a profile is only an initial batch set.
 */
object HardwareKeyProfiles {

    const val BLACKBERRY = "blackberry"
    const val TT2 = "tt2"
    const val TITAN2_ELITE = "titan2_elite"

    /** All available profile ids, in display order. */
    fun ids(): List<String> = listOf(BLACKBERRY, TT2, TITAN2_ELITE)

    /**
     * Single source of truth for the 11 hardware-keyboard key-binding preferences, in canonical
     * order. The value lists ([blackberryValues] / [tt2Values]) are defined in the same order, so
     * every profile implementation (`get` / `applyProfile` / `candidateKeys`) is built by
     * `keyBindings(hw) zip values`, eliminating the duplicate `hw.xxx.key` listings that
     * previously appeared in `blackberry` / `tt2` / `applyProfile` and could drift apart.
     */
    private fun keyBindings(hw: AppPrefs.HardwareKeyboard): List<ManagedPreference.PString> = listOf(
        hw.candidate1Key,
        hw.candidate2Key,
        hw.candidate3Key,
        hw.candidate4Key,
        hw.candidate5Key,
        hw.pageNextKey,
        hw.pagePrevKey,
        hw.symbolPickerKey,
        hw.toggleImeKey,
        hw.pickerKey,
        hw.altLatchKey,
    )

    private val blackberryValues = listOf(
        "space", "0", "Alt_R", "Shift_L", "Shift_R",
        "grave", "Alt+grave", "Alt_R", "Alt+space", "Shift+space", "Alt_L",
    )

    private val tt2Values = listOf(
        "space", "Control_L", "Tab", "Shift_L", "Alt_R",
        "", "", "", "Alt+space", "Shift+space", "Alt_R",
    )

    /**
     * Titan2 Elite. Its bottom row (`TitanKey.kl` ROW4) is
     * `左Shift / 返回 / home / 空格 / 后台任务 / fn / 右Shift`, but **home and 后台任务 cannot be
     * bound at all**: they report `KEYCODE_HOME` / `KEYCODE_APP_SWITCH`, which the window policy
     * consumes before any window — including the IME window — so no input method ever receives them
     * (measured: pressing either produces zero events on the IME side, while their keyCodes are
     * already present in the shortcut tables).
     *
     * What remains is exactly five bottom-row keys, symmetric around 空格, so the full 巨硬
     * (4-2-1-3-5) row still works: `左Shift=4th, 返回=2nd, 空格=1st (centre), fn=3rd, 右Shift=5th`.
     *
     * Paging therefore moves off the Shifts (they are candidate keys now) onto DPAD left/right —
     * pending a measurement of whether DPAD events reach the input method on this keyboard.
     * `altLatchKey` is a bare `Alt_L` because this keyboard has no right Alt (only `KEY_LEFTALT`).
     */
    private val titan2EliteValues = listOf(
        "space",        // candidate1Key     空格 —— 巨硬首选字（居中）
        "NavBack",      // candidate2Key     返回
        "NavFn",        // candidate3Key     fn
        "Shift_L",      // candidate4Key     左Shift
        "Shift_R",      // candidate5Key     右Shift
        "Sym",        // pageNextKey       
        "Alt+Sym",         // pagePrevKey       
        "NavFn",          // symbolPickerKey   SYM 键
        "Alt+space",    // toggleImeKey
        "Shift+space",  // pickerKey
        "Alt_L",        // altLatchKey       本机只有左 Alt
    )

    private fun valuesFor(name: String): List<String> = when (name) {
        TT2 -> tt2Values
        TITAN2_ELITE -> titan2EliteValues
        else -> blackberryValues
    }

    /** Resolve the key-binding map for the given profile id (defaults to [BLACKBERRY]). */
    fun get(name: String, hw: AppPrefs.HardwareKeyboard): Map<String, String> =
        keyBindings(hw).zip(valuesFor(name)).associate { (pref, value) -> pref.key to value }

    /**
     * Overwrite every individual key-binding preference with the values of the given profile.
     * Centralised here so the settings screen and the first-run initialiser share one code path
     * — the profile list is the single source of truth, eliminating the class of bug where a
     * key's factory default drifted out of sync with the preset (which left the next-page key
     * dead on a fresh install until a preset was re-selected).
     */
    fun applyProfile(name: String, hw: AppPrefs.HardwareKeyboard) {
        keyBindings(hw).zip(valuesFor(name)).forEach { (pref, value) -> pref.setValue(value) }
    }

    /**
     * Single source of truth for the four "candidate2-5" preference references. Used by
     * [candidateKeys] (to seed profile defaults), [clearCandidateKeys] (to wipe them when
     * switching to 巨硬 → 普通), and the settings UI to identify the four rows whose
     * visibility is driven by the candidate display mode.
     */
    private fun candidate2to5(hw: AppPrefs.HardwareKeyboard): List<ManagedPreference.PString> = listOf(
        hw.candidate2Key,
        hw.candidate3Key,
        hw.candidate4Key,
        hw.candidate5Key,
    )

    /**
     * Returns only the candidate2-5 (preference, value) pairs for the given profile. Values are
     * looked up by the same [valuesFor] list that [applyProfile] uses, so this method never
     * redefines a key string and there is exactly one place to update per profile.
     *
     * Used by the "候选显示模式 = 巨硬" re-entry path to re-seed the four physical selection keys
     * after a round trip through "普通" (which clears them) — without touching
     * [AppPrefs.HardwareKeyboard.candidate1Key] (first-pick, stays bound to Space) or the
     * paging/symbol/global keys.
     */
    fun candidateKeys(name: String, hw: AppPrefs.HardwareKeyboard): List<Pair<ManagedPreference.PString, String>> {
        val bindings = keyBindings(hw)
        val values = valuesFor(name)
        return candidate2to5(hw).mapNotNull { pref ->
            bindings.indexOf(pref).takeIf { it >= 0 }?.let { pref to values[it] }
        }
    }

    /**
     * Apply only the candidate2-5 portion of the given profile. See [candidateKeys].
     */
    fun applyCandidateKeys(name: String, hw: AppPrefs.HardwareKeyboard) {
        candidateKeys(name, hw).forEach { (pref, value) -> pref.setValue(value) }
    }

    /**
     * Clear all four candidate2-5 preferences to empty strings — used by the
     * "候选显示模式 = 普通" entry path to disable the bottom-row physical-key quick-pick shortcuts
     * at runtime. Symmetric to [applyCandidateKeys] but writes "" instead of the profile values.
     */
    fun clearCandidateKeys(hw: AppPrefs.HardwareKeyboard) {
        candidate2to5(hw).forEach { it.setValue("") }
    }
}
