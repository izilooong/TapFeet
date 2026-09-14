/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.data.prefs

import android.view.KeyEvent
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/**
 * Registry of "pseudo keys": physical function keys that have **no fcitx5 KeySym**, and are
 * therefore bound by a name string instead of a fcitx5 portableString.
 *
 * Their Android keyCodes are absent from the codegen'd `FcitxKeyMapping.keyCodeToSym` whitelist
 * (`codegen/GenKeyMapping.kt`), which means:
 *  - `KeySym.fromKeyEvent` returns null, so `FcitxInputMethodService.forwardKeyEvent` logs
 *    "Skipped KeyEvent" and lets the event fall through to the system. **That is exactly what we
 *    want**: giving `KEYCODE_BACK` a fcitx5 key name would make it `XK_Back` (= `0xff08` =
 *    BackSpace), so every press of the back key would be forwarded to the engine as a backspace
 *    and delete a character;
 *  - `Key.parse(portableString)` cannot express them either.
 *
 * This follows the pre-existing `"Sym"` precedent, now generalised: every consumer must consult
 * this registry **before** calling `Key.parse`. The parse sites are
 * [org.fcitx.fcitx5.android.input.candidates.HardwareShortcutResolver],
 * [org.fcitx.fcitx5.android.input.InputView],
 * [org.fcitx.fcitx5.android.ui.main.settings.KeyCaptureUi] and
 * `FcitxInputMethodService.isAltLatchKey`.
 *
 * ⚠️ When adding a new parse site, check this registry first — entry names deliberately avoid the
 * fcitx5 native key names (`Back`/`Home`/`Fn` all collide, hence the `Nav` prefix), so a missed
 * registry lookup would silently mis-resolve the binding.
 */
object HardwareSpecialKeys {

    class Entry(val name: String, @StringRes val labelRes: Int, val keyCodes: IntArray) {
        fun matches(keyCode: Int): Boolean = keyCodes.contains(keyCode)
    }

    private val entries: List<Entry> = listOf(
        // The BlackBerry-class SYM key: some devices report KEYCODE_SYM, others KEYCODE_PICTSYMBOLS.
        Entry(
            "Sym",
            R.string.hw_special_sym,
            intArrayOf(KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_PICTSYMBOLS)
        ),
        Entry("NavBack", R.string.hw_special_back, intArrayOf(KeyEvent.KEYCODE_BACK)),
        // fn / FUNC3. Measured on the Titan keyboard (keylayout ROW4 `key 251 FUNC3`): kernel
        // scancode 251 arrives with keyCode **403** — a late/MediaTek keycode, NOT the canonical
        // KEYCODE_FUNCTION (119) — and it DOES reach the input method (confirmed through the IME's
        // own "Skipped KeyEvent" log). 119 is kept as well: it is the documented Android FUNCTION
        // code and a keylayout may legitimately resolve to it.
        Entry("NavFn", R.string.hw_special_fn, intArrayOf(403, KeyEvent.KEYCODE_FUNCTION)),
        // NOTE: KEYCODE_HOME / KEYCODE_APP_SWITCH are deliberately absent. They are system keys
        // consumed by the window policy before *any* window — including the IME window — so a
        // binding for them could never fire on any device. Verified on the Titan keyboard: pressing
        // home / recents produces zero events on the IME side while their keyCodes are otherwise
        // present in the shortcut tables.
    )

    private val byName: Map<String, Entry> = entries.associateBy { it.name }
    private val byKeyCode: Map<Int, Entry> =
        entries.flatMap { e -> e.keyCodes.map { it to e } }.toMap()

    /** Whether [name] is a registered pseudo key (so callers must NOT feed it to `Key.parse`). */
    fun isSpecialName(name: String): Boolean = byName.containsKey(name)

    /** Entry for a stored binding string; null for ordinary fcitx5 portableStrings. */
    fun entryForName(name: String): Entry? = byName[name]

    /** Entry for a physical keyCode; null for ordinary keys. Used by the key-capture dialog. */
    fun entryForKeyCode(keyCode: Int): Entry? = byKeyCode[keyCode]
}
