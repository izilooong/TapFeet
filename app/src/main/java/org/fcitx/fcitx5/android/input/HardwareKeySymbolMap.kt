/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles

/**
 * Maps a physical key (Android [KeyEvent] keyCode) to the symbol printed on that keycap, so a
 * long-press on the key inputs the symbol directly (BlackBerry-style "press-and-hold for the
 * keycap symbol").
 *
 * The mapping is **per hardware-keyboard profile** ([HardwareKeyProfiles]): each device (BlackBerry,
 * TT2, ...) has its own keycaps, so the symbols are resolved from the currently selected
 * [AppPrefs.HardwareKeyboard.keyProfile]. Switching the profile in settings takes effect immediately.
 *
 * This is data only — edit the per-device map below to retune the symbols for a device's keycaps.
 *
 * - The keycap symbols are the corrected BlackBerry (Q25) values.
 *
 * Keys not listed here keep their existing behaviour:
 *  - Shift_Left/Right, SYM/Alt_Right, Space are bound to candidate selection / symbol picker.
 *  - Alt/Ctrl modifiers are excluded.
 *  - 0 is listed (long-press → "$"); its short press still picks candidate 1.
 */
object HardwareKeySymbolMap {

    // BlackBerry (Q25): keycap symbol per physical key. Long-press a key inputs its symbol;
    // a quick tap keeps the original behaviour (e.g. 0 still picks candidate 1 on short press).
    private val blackberrySymbolMap: Map<Int, String> = mapOf(
        // Number keycap symbol (0 only; 1-9 short-press already types the digit as usual).
        
        // Letters: BlackBerry keycap symbols (corrected for Q25).
        KeyEvent.KEYCODE_Q to "#",
        KeyEvent.KEYCODE_W to "1",
        KeyEvent.KEYCODE_E to "2",
        KeyEvent.KEYCODE_R to "3",
        KeyEvent.KEYCODE_T to "(",
        KeyEvent.KEYCODE_Y to ")",
        KeyEvent.KEYCODE_U to "_",
        KeyEvent.KEYCODE_I to "-",
        KeyEvent.KEYCODE_O to "+",
        KeyEvent.KEYCODE_P to "@",
        KeyEvent.KEYCODE_A to "*",
        KeyEvent.KEYCODE_S to "4",
        KeyEvent.KEYCODE_D to "5",
        KeyEvent.KEYCODE_F to "6",
        KeyEvent.KEYCODE_G to "/",
        KeyEvent.KEYCODE_H to ":",
        KeyEvent.KEYCODE_J to ";",
        KeyEvent.KEYCODE_K to "'",
        KeyEvent.KEYCODE_L to "\"",
        KeyEvent.KEYCODE_Z to "7",
        KeyEvent.KEYCODE_X to "8",
        KeyEvent.KEYCODE_C to "9",
        KeyEvent.KEYCODE_V to "?",
        KeyEvent.KEYCODE_B to "!",
        KeyEvent.KEYCODE_N to ",",
        KeyEvent.KEYCODE_M to "."
    )

    // TT2 device: same number-row symbols; letter symbols to be verified for the TT2 keycaps.
    private val tt2SymbolMap: Map<Int, String> = mapOf(
     
        KeyEvent.KEYCODE_Q to "0",
        KeyEvent.KEYCODE_W to "1",
        KeyEvent.KEYCODE_E to "2",
        KeyEvent.KEYCODE_R to "3",
        KeyEvent.KEYCODE_T to "(",
        KeyEvent.KEYCODE_Y to ")",
        KeyEvent.KEYCODE_U to "-",
        KeyEvent.KEYCODE_I to "_",
        KeyEvent.KEYCODE_O to "/",
        KeyEvent.KEYCODE_P to ":",
        KeyEvent.KEYCODE_A to "@",
        KeyEvent.KEYCODE_S to "4",
        KeyEvent.KEYCODE_D to "5",
        KeyEvent.KEYCODE_F to "6",
        KeyEvent.KEYCODE_G to "*",
        KeyEvent.KEYCODE_H to "#",
        KeyEvent.KEYCODE_J to "+",
        KeyEvent.KEYCODE_K to "\"",
        KeyEvent.KEYCODE_L to "'",
        KeyEvent.KEYCODE_Z to "!",
        KeyEvent.KEYCODE_X to "7",
        KeyEvent.KEYCODE_C to "8",
        KeyEvent.KEYCODE_V to "9",
        KeyEvent.KEYCODE_B to ".",
        KeyEvent.KEYCODE_N to ",",
        KeyEvent.KEYCODE_M to "?"
    )

    /** Returns the symbol map for the given profile id (defaults to [HardwareKeyProfiles.BLACKBERRY]). */
    fun symbolMapFor(profileName: String): Map<Int, String> = when (profileName) {
        HardwareKeyProfiles.TT2 -> tt2SymbolMap
        else -> blackberrySymbolMap
    }

    /** The symbol map for the currently selected hardware-keyboard profile. */
    private fun currentMap(): Map<Int, String> =
        symbolMapFor(AppPrefs.getInstance().hardwareKeyboard.keyProfile.getValue())

    /** Returns the symbol for [keyCode] under the active profile, or null if it has no long-press symbol. */
    fun symbolForKeyCode(keyCode: Int): String? = currentMap()[keyCode]

    /** Whether [keyCode] participates in long-press-to-symbol under the active profile. */
    fun contains(keyCode: Int): Boolean = currentMap().containsKey(keyCode)
}
