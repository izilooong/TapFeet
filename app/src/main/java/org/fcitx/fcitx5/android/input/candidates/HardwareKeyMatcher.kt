/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.view.KeyEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.HardwareChord

/**
 * Pure matching core: decides whether a [KeyPress] hits a [HardwareShortcutResolver.ParsedKey].
 *
 * Zero preferences / `Context` / `View` dependencies — deliberately a separate file from
 * [HardwareShortcutResolver] so unit tests can exercise every rule through this interface alone
 * (hand-built [KeyPress] + hand-built [HardwareShortcutResolver.ParsedKey]), while the resolver
 * remains the facade that reads the user's key bindings and memoizes parse results.
 *
 * All historical `Alt+grave` / sticky-Alt / chord bugs concentrate in the rules below; each rule
 * carries a named test in HardwareKeyMatcherTest.
 *
 * internal: the interface it speaks ([HardwareShortcutResolver.ParsedKey]) is itself internal —
 * the matcher is the resolver's private core, not a module for outside consumers.
 */
internal object HardwareKeyMatcher {

    /**
     * Raw Alt/Ctrl/Shift/Meta bits of [kp], exactly as pressed — without the number/symbol
     * stripping that `KeyStates.fromKeyEvent` applies. Mirrors `KeyStates.rawModifierStates`
     * over the snapshot's metaState so no KeyEvent is needed.
     */
    private fun rawModifierStates(kp: KeyPress): KeyStates {
        var s = KeyState.NoState.state
        if (kp.metaState and KeyEvent.META_ALT_ON != 0) s = s or KeyState.Alt.state
        if (kp.metaState and KeyEvent.META_CTRL_ON != 0) s = s or KeyState.Ctrl.state
        if (kp.metaState and KeyEvent.META_SHIFT_ON != 0) s = s or KeyState.Shift.state
        if (kp.metaState and KeyEvent.META_META_ON != 0) s = s or KeyState.Meta.state
        return KeyStates(s and KeyState.SimpleMask.state)
    }

    /** Whether [sym] is hit by this press, via the keyCode→keysym map or the produced unicode. */
    private fun symMatches(kp: KeyPress, sym: Int): Boolean {
        val symFromKeyCode = FcitxKeyMapping.keyCodeToSym(kp.keyCode)
        return symFromKeyCode == sym ||
            (kp.unicodeChar != 0 && kp.unicodeChar == sym)
    }

    fun matchesParsedKey(kp: KeyPress, parsed: HardwareShortcutResolver.ParsedKey?): Boolean =
        when (parsed) {
            null -> false
            is HardwareShortcutResolver.ParsedKey.Special -> parsed.entry.matches(kp.keyCode)
            is HardwareShortcutResolver.ParsedKey.Ref -> matches(kp, parsed.key)
            // 和弦：修饰键按住 **且** 内层键命中。顺序要紧：内层键（如 "e"）本身不带 modifier，
            // 会被 matches 的「纯键」分支当成普通按键命中，所以必须先过修饰键这一关。
            is HardwareShortcutResolver.ParsedKey.Chord ->
                modifierHeld(parsed.modifier, kp) && matchesParsedKey(kp, parsed.inner)
        }

    fun matches(kp: KeyPress, key: Key): Boolean {
        if (key.sym == 0) return false
        // Match by the physical key's keysym OR the character it produces. We must also accept the
        // keyCode-derived keysym because holding a modifier (e.g. Alt) can change unicodeChar into
        // a composed character, which would otherwise make the sym comparison fail for symbol keys
        // like grave (`) and break combos such as "Alt+grave". Character keys whose keyCode is
        // unreliable across layouts (e.g. `$`) still match via the produced unicode.
        if (!symMatches(kp, key.sym)) return false
        if (KeyStates.isModifierKeySym(key.sym)) return true
        // A configured COMBO (has modifier, e.g. "Alt+grave") must match the modifier exactly, so
        // use raw states (no stripping). A plain key (no modifier) must ignore the system's
        // residual modifier state — notably Alt sticky/locked left by some ROMs after an Alt tap —
        // so the shortcut still works in editors where that happens. (The old KeyEvent path read
        // KeyStates.fromKeyEvent's tolerant stripping, which special-cases unicode == ' ' and made
        // the first-pick Space shortcut fail while keys 2-5 kept working; comparing plain keys
        // against an empty state covers Space too.)
        val states = if (key.states != 0) rawModifierStates(kp).toInt() else 0
        return states == key.states
    }

    /**
     * Match by KeySym only (any modifier state) — used to detect a physical key regardless of
     * modifiers. A chord binding still requires its modifier held.
     */
    fun matchesKeySymOnly(kp: KeyPress, parsed: HardwareShortcutResolver.ParsedKey): Boolean =
        when (parsed) {
            is HardwareShortcutResolver.ParsedKey.Special -> parsed.entry.matches(kp.keyCode)
            is HardwareShortcutResolver.ParsedKey.Ref -> symMatches(kp, parsed.key.sym)
            is HardwareShortcutResolver.ParsedKey.Chord ->
                modifierHeld(parsed.modifier, kp) && matchesKeySymOnly(kp, parsed.inner)
        }

    /**
     * Whether the chord modifier [name] counts as held for [kp]. Two families:
     *  - pseudo modifiers (`Fn` / `Sym`): no fcitx5 keysym, no meta bit — the union of system
     *    meta and our tracked press state was frozen into [KeyPress.fnHeld] / [KeyPress.symHeld]
     *    at snapshot time;
     *  - side-precise real modifiers (`Alt_L/R`, `Shift_L/R`): meta carries the side, read it.
     *
     * Modifier names are `HardwareChord` `const val`s (inlined at compile time; the object's
     * runtime held-state is never touched here — that knowledge lives in KeyPress).
     */
    fun modifierHeld(name: String, kp: KeyPress): Boolean = when (name) {
        HardwareChord.FN -> kp.fnHeld
        HardwareChord.SYM -> kp.symHeld
        HardwareChord.ALT_L -> kp.metaState and KeyEvent.META_ALT_LEFT_ON != 0
        HardwareChord.ALT_R -> kp.metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        HardwareChord.SHIFT_L -> kp.metaState and KeyEvent.META_SHIFT_LEFT_ON != 0
        HardwareChord.SHIFT_R -> kp.metaState and KeyEvent.META_SHIFT_RIGHT_ON != 0
        else -> false
    }

    /**
     * Resolve paging direction from [kp]. Returns -1 (previous page), 1 (next page),
     * or null when the press does not match either paging key.
     */
    fun pagingDirection(
        kp: KeyPress,
        nextParsed: HardwareShortcutResolver.ParsedKey?,
        prevParsed: HardwareShortcutResolver.ParsedKey?,
    ): Int? {
        val nextMatches = matchesParsedKey(kp, nextParsed)
        val prevMatches = matchesParsedKey(kp, prevParsed)
        if (!nextMatches && !prevMatches) return null
        // A combo (modifier) binding takes precedence over a plain binding on the same physical key,
        // so e.g. "Alt+grave" (prev) is not stolen by a plain "grave" (next) binding.
        val prevHasModifier = (prevParsed as? HardwareShortcutResolver.ParsedKey.Ref)?.key?.states != 0
        val nextHasModifier = (nextParsed as? HardwareShortcutResolver.ParsedKey.Ref)?.key?.states != 0
        return when {
            prevMatches && prevHasModifier -> -1
            nextMatches && nextHasModifier -> 1
            prevMatches -> -1
            else -> 1
        }
    }
}
