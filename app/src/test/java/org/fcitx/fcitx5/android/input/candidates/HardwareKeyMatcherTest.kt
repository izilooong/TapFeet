/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.view.KeyEvent
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.KeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the hardware shortcut matching core ([HardwareKeyMatcher]).
 *
 * These are the rules every historical `Alt+grave` / sticky-Alt / chord bug report boils down
 * to. Hand-built [KeyPress] snapshots replace `android.view.KeyEvent` so the whole matrix runs
 * on the JVM; string→Key parsing (`Key.parse`, JNI) is deliberately NOT covered here — it is
 * verified on-device through the Lab key-capture page.
 *
 * Keysym values mirror fcitx5 X11 codes: grave = 0x60, space = 0x20, dollar = 0x24,
 * Alt_L = 0xffe9, Alt_R = 0xffea, Shift_R = 0xffe2, letters are their ASCII codes.
 */
class HardwareKeyMatcherTest {

    private val symAltL = 0xffe9
    private val symShiftR = 0xffe2
    private val stateAlt = KeyState.Alt.state.toInt()

    /** Bare fcitx5 Key — for [HardwareKeyMatcher.matches]. */
    private fun key(sym: Int, states: Int = 0) = Key(sym, states, "", "")

    /** Wrapped binding — for [HardwareKeyMatcher.pagingDirection] / [matchesKeySymOnly] / Chord inner. */
    private fun binding(sym: Int, states: Int = 0): HardwareShortcutResolver.ParsedKey.Ref =
        HardwareShortcutResolver.ParsedKey.Ref(key(sym, states))

    private fun press(
        keyCode: Int,
        unicode: Int = 0,
        meta: Int = 0,
        repeat: Int = 0,
        fn: Boolean = false,
        sym: Boolean = false,
    ) = KeyPress(keyCode, unicode, meta, repeat, fn, sym)

    private val grave = KeyEvent.KEYCODE_GRAVE            // keyCodeToSym → 0x60
    private val space = KeyEvent.KEYCODE_SPACE            // keyCodeToSym → 0x20
    private val keyE = KeyEvent.KEYCODE_E                 // keyCodeToSym → 'e'
    private val keyD = KeyEvent.KEYCODE_D                 // keyCodeToSym → 'd'
    private val dollar = KeyEvent.KEYCODE_POUND           // keyCodeToSym → 0x23, NOT 0x24

    // ---------------------------------------------------------------- rule 1: combos match modifiers exactly

    @Test
    fun comboBindingRequiresModifierExactly() {
        val altGrave = key(0x60, stateAlt)
        // Alt+grave pressed → prev-page combo hits.
        assertTrue(HardwareKeyMatcher.matches(press(grave, meta = KeyEvent.META_ALT_ON), altGrave))
        // bare grave must NOT hit the combo (this is what keeps "grave" as next-page usable)
        assertFalse(HardwareKeyMatcher.matches(press(grave), altGrave))
        // wrong modifier (Ctrl+grave) must not hit either
        assertFalse(
            HardwareKeyMatcher.matches(press(grave, meta = KeyEvent.META_CTRL_ON), altGrave)
        )
    }

    // ---------------------------------------------------------------- rule 2: plain keys tolerate residual modifiers

    @Test
    fun plainBindingIgnoresResidualModifiers() {
        val plainSpace = key(0x20)
        // Sticky Alt left by some ROMs after an Alt tap must not break the first-pick shortcut.
        // (The old KeyEvent path special-cased unicode == ' ' and broke exactly this.)
        assertTrue(
            HardwareKeyMatcher.matches(
                press(space, unicode = ' '.code, meta = KeyEvent.META_ALT_ON), plainSpace
            )
        )
        assertTrue(HardwareKeyMatcher.matches(press(space), plainSpace))
    }

    // ---------------------------------------------------------------- rule 3: unicode fallback

    @Test
    fun unicodeFallbackForUnreliableKeyCodeMap() {
        // `$` (0x24): keyCodeToSym(KEYCODE_POUND) = numbersign (0x23), so the map misses;
        // the produced unicode is the only thing that can match.
        val dollarBinding = key(0x24)
        assertTrue(HardwareKeyMatcher.matches(press(dollar, unicode = 0x24), dollarBinding))
        assertFalse(HardwareKeyMatcher.matches(press(dollar, unicode = 0x23), dollarBinding))
    }

    // ---------------------------------------------------------------- rule 4: pure modifier syms short-circuit

    @Test
    fun pureModifierSymIgnoresStates() {
        val altL = key(symAltL)
        // A binding of "Alt_L" matches the physical left-Alt press regardless of residual meta.
        assertTrue(
            HardwareKeyMatcher.matches(
                press(KeyEvent.KEYCODE_ALT_LEFT, meta = KeyEvent.META_SHIFT_ON), altL
            )
        )
        assertFalse(HardwareKeyMatcher.matches(press(KeyEvent.KEYCODE_ALT_RIGHT), altL))
    }

    @Test
    fun zeroSymNeverMatches() {
        assertFalse(HardwareKeyMatcher.matches(press(grave), Key(0, 0, "", "")))
    }

    // ---------------------------------------------------------------- rule 5: paging arbitration (combo precedence)

    private val prevAltGrave = binding(0x60, stateAlt)   // pagePrevKey = "Alt+grave"
    private val nextPlainGrave = binding(0x60)           // pageNextKey = "grave"

    @Test
    fun pagingComboBeatsPlainOnSamePhysicalKey() {
        assertEquals(
            -1,
            HardwareKeyMatcher.pagingDirection(press(grave, meta = KeyEvent.META_ALT_ON), nextPlainGrave, prevAltGrave)
        )
        // bare grave falls to the plain next binding
        assertEquals(
            1,
            HardwareKeyMatcher.pagingDirection(press(grave), nextPlainGrave, prevAltGrave)
        )
        // unrelated key: no paging at all
        assertNull(HardwareKeyMatcher.pagingDirection(press(space), nextPlainGrave, prevAltGrave))
        // unbound prev (null) still resolves plain next
        assertEquals(1, HardwareKeyMatcher.pagingDirection(press(grave), nextPlainGrave, null))
    }

    // ---------------------------------------------------------------- rule 6: chord bindings

    @Test
    fun chordRequiresModifierHeld() {
        val fnE = HardwareShortcutResolver.ParsedKey.Chord(
            org.fcitx.fcitx5.android.data.prefs.HardwareChord.FN,
            HardwareShortcutResolver.ParsedKey.Ref(key('e'.code))
        )
        // Fn NOT held: the inner key alone must not hit (it would steal plain typing).
        assertFalse(HardwareKeyMatcher.matchesParsedKey(press(keyE), fnE))
        // Fn held + inner key → hit.
        assertTrue(HardwareKeyMatcher.matchesParsedKey(press(keyE, fn = true), fnE))
        // Fn held but different key → no.
        assertFalse(HardwareKeyMatcher.matchesParsedKey(press(keyD, fn = true), fnE))
    }

    @Test
    fun sidePreciseRealModifierChordReadsMetaSide() {
        val shiftRE = HardwareShortcutResolver.ParsedKey.Chord(
            org.fcitx.fcitx5.android.data.prefs.HardwareChord.SHIFT_R,
            HardwareShortcutResolver.ParsedKey.Ref(key('e'.code))
        )
        assertFalse(HardwareKeyMatcher.matchesParsedKey(press(keyE, meta = KeyEvent.META_SHIFT_LEFT_ON), shiftRE))
        assertTrue(HardwareKeyMatcher.matchesParsedKey(press(keyE, meta = KeyEvent.META_SHIFT_RIGHT_ON), shiftRE))
    }

    // ---------------------------------------------------------------- key-sym-only matching (latch path)

    @Test
    fun keySymOnlyIgnoresModifierStates() {
        // Alt-latch trigger is matched keysym-only: residual modifiers (or a combo-style stored
        // value) never block it.
        val altL = binding(symAltL)
        assertTrue(
            HardwareKeyMatcher.matchesKeySymOnly(press(KeyEvent.KEYCODE_ALT_LEFT, meta = KeyEvent.META_CTRL_ON), altL)
        )
        assertFalse(HardwareKeyMatcher.matchesKeySymOnly(press(space), altL))
    }

    @Test
    fun modifierKeySymShortCircuitAppliesToRawStatesBranch() {
        // sanity: Shift_R binding (0xffe2) hits physical right shift, not left.
        val shiftR = key(symShiftR)
        assertTrue(HardwareKeyMatcher.matches(press(KeyEvent.KEYCODE_SHIFT_RIGHT), shiftR))
        assertFalse(HardwareKeyMatcher.matches(press(KeyEvent.KEYCODE_SHIFT_LEFT), shiftR))
    }
}
