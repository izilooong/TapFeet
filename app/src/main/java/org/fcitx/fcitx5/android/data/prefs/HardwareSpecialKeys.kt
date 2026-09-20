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

    class Entry(
        val name: String,
        @StringRes val labelRes: Int,
        val keyCodes: IntArray,
        /**
         * 该伪键同时是**和弦修饰键**时，存储串里用的前缀名（`Fn` / `Sym`）；null = 纯动作键。
         *
         * ⚠️ 注册名（`NavFn`）与键帽丝印名（`Fn`）**不是同一个串** —— 注册名刻意加 `Nav` 前缀去避开
         * fcitx5 原生键名。两者只能在这里显式对上；靠「它们应该一样吧」去猜，结果就是
         * [HardwareChord.setHeld] 拿注册名去比 `"Fn"` 永远为假、`fnDown` 永远是 false、
         * `Fn+字母` 静默失效（界面看着配好了，按下去毫无反应，符号窗口照旧）。
         */
        val chordModifier: String? = null,
    ) {
        fun matches(keyCode: Int): Boolean = keyCodes.contains(keyCode)
    }

    private val entries: List<Entry> = listOf(
        // The SYM key: a dedicated physical button on BlackBerry-class keyboards. Matched ONLY by
        // the documented Android codes — KEYCODE_SYM / KEYCODE_PICTSYMBOLS.
        //
        // ⚠️ **KEYCODE_ALT_RIGHT is deliberately NOT listed here.** The right-Alt modifier and the
        // SYM button are two *independent* physical keys and must be identified separately:
        //  - right Alt is a real modifier → flows through the fcitx5 keysym path as `Alt_R`;
        //  - the SYM button is this pseudo key → matched by the name `Sym`.
        // Conflating the two (listing ALT_RIGHT here) made a right-Alt press be mis-identified as a
        // `Sym` pseudo key, so it triggered `Sym` bindings (e.g. the symbol window) instead of
        // behaving as Alt, and the two real keys could never be bound to distinct actions.
        //
        // The BlackBerry profile spells the symbol-picker key as the raw keysym `"Alt_R"`, which
        // matches a right-Alt press via the keysym path — independent of this pseudo key.
        Entry(
            "Sym",
            R.string.hw_special_sym,
            intArrayOf(
                KeyEvent.KEYCODE_SYM,
                KeyEvent.KEYCODE_PICTSYMBOLS,
            ),
            HardwareChord.SYM,
        ),
        Entry("NavBack", R.string.hw_special_back, intArrayOf(KeyEvent.KEYCODE_BACK)),
        // fn / FUNC3. Measured on the Titan keyboard (keylayout ROW4 `key 251 FUNC3`): kernel
        // scancode 251 arrives with keyCode **403** — a late/MediaTek keycode, NOT the canonical
        // KEYCODE_FUNCTION (119) — and it DOES reach the input method (confirmed through the IME's
        // own "Skipped KeyEvent" log). 119 is kept as well: it is the documented Android FUNCTION
        // code and a keylayout may legitimately resolve to it.
        Entry(
            "NavFn",
            R.string.hw_special_fn,
            intArrayOf(403, KeyEvent.KEYCODE_FUNCTION),
            HardwareChord.FN,
        ),
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
