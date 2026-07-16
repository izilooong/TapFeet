/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

/**
 * Predefined sets ("profiles") of hardware keyboard key bindings.
 *
 * Each profile maps a [AppPrefs.HardwareKeyboard] key-binding preference key to its fcitx5
 * portableString value (e.g. "Alt+space", "dollar", "Shift_L", or the special "Sym" string).
 * Selecting a profile in the settings screen overwrites every individual key binding with the
 * profile's values.
 *
 * [BLACKBERRY] is the original/default set of bindings. [TT2] is an alternative layout.
 * The individual keys remain editable afterwards, so a profile is only an initial batch set.
 */
object HardwareKeyProfiles {

    const val BLACKBERRY = "blackberry"
    const val TT2 = "tt2"

    /** All available profile ids, in display order. */
    fun ids(): List<String> = listOf(BLACKBERRY, TT2)

    /** Resolve the key-binding map for the given profile id (defaults to [BLACKBERRY]). */
    fun get(name: String, hw: AppPrefs.HardwareKeyboard): Map<String, String> = when (name) {
        TT2 -> tt2(hw)
        else -> blackberry(hw)
    }

    private fun blackberry(hw: AppPrefs.HardwareKeyboard) = mapOf(
        hw.candidate1Key.key to "space",
        hw.candidate2Key.key to "0",
        hw.candidate3Key.key to "Alt_R",
        hw.candidate4Key.key to "Shift_L",
        hw.candidate5Key.key to "Shift_R",
        hw.pageNextKey.key to "grave",
        hw.pagePrevKey.key to "Alt+grave",
        hw.symbolPickerKey.key to "Alt_R",
        hw.toggleImeKey.key to "Alt+space",
        hw.pickerKey.key to "Shift+space",
        hw.altLatchKey.key to "Alt_L",
    )

    private fun tt2(hw: AppPrefs.HardwareKeyboard) = mapOf(
        hw.candidate1Key.key to "space",
        hw.candidate2Key.key to "Control_L",
        hw.candidate3Key.key to "Tab",
        hw.candidate4Key.key to "Shift_L",
        hw.candidate5Key.key to "Alt_R",
        hw.pageNextKey.key to "",
        hw.pagePrevKey.key to "",
        hw.symbolPickerKey.key to "",
        hw.toggleImeKey.key to "Alt+space",
        hw.pickerKey.key to "Shift+space",
        hw.altLatchKey.key to "Alt_R",
    )
}
