/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input

import android.view.KeyEvent

/**
 * Maps a physical key (Android [KeyEvent] keyCode) to the symbol printed on that keycap, so a
 * long-press on the key inputs the symbol directly (BlackBerry-style "press-and-hold for the
 * keycap symbol").
 *
 * This is data only — edit this single map to retune the symbols for your Q25 keycaps.
 *
 * - The number-row symbols (! @ # $ % ^ & * () are the authentic BlackBerry keycap symbols.
 * - The letter-key symbols follow the BlackBerry 10 SYM matrix but may need verification
 *   against your device — tweak freely.
 *
 * Keys intentionally NOT listed here keep their existing behaviour:
 *  - 0, Shift_Left/Right, SYM/Alt_Right, Space are bound to candidate selection / symbol picker.
 *  - Alt/Ctrl modifiers are excluded.
 */
object HardwareKeySymbolMap {

    private val map = mapOf(
        //  BlackBerry just 0 key number keycap symbols.
        KeyEvent.KEYCODE_0 to "$",
      

        // Letters: BlackBerry 10 SYM-style matrix (VERIFY against your Q25 keycaps).
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

    /** Returns the symbol for [keyCode], or null if the key has no long-press symbol. */
    fun symbolForKeyCode(keyCode: Int): String? = map[keyCode]

    /** Whether [keyCode] participates in long-press-to-symbol. */
    fun contains(keyCode: Int): Boolean = map.containsKey(keyCode)
}
