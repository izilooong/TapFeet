/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.view.KeyEvent
import org.fcitx.fcitx5.android.data.prefs.HardwareChord

/**
 * Android-free snapshot of one physical key press — the seam between the `KeyEvent` world and
 * the hardware shortcut matching core ([HardwareKeyMatcher]).
 *
 * `android.view.KeyEvent` cannot be constructed on the JVM (its constructor touches
 * SystemClock stubs), so the matcher core deliberately does not see it: every entry point takes a
 * snapshot via [toKeyPress] first, and tests substitute a hand-built [KeyPress] instead. The
 * snapshot is taken at key-dispatch time, so [fnHeld] / [symHeld] freeze the pseudo-chord
 * "held" state ([HardwareChord.fnDown] / [HardwareChord.symDown]) exactly as of this press.
 */
data class KeyPress(
    val keyCode: Int,
    val unicodeChar: Int,
    val metaState: Int,
    val repeatCount: Int,
    /** Whether the `Fn` chord modifier counts as held for this press. */
    val fnHeld: Boolean,
    /** Whether the `Sym` chord modifier counts as held for this press. */
    val symHeld: Boolean,
)

/**
 * Capture this event as a [KeyPress]. Call this once at each dispatch entry point
 * (service key handling, InputView / CandidatesView hardware handlers, probe logging), never
 * inside the matching core.
 *
 * ⚠️ [symHeld] must NOT fall back to `isAltPressed`: META_ALT_ON cannot tell left from right Alt,
 * and the BlackBerry SYM button reports as right Alt while the LEFT Alt + letter is the system's
 * native key-cap symbol input (Alt-latch territory). Falling back there used to swallow the
 * key-cap symbols. The tracked [HardwareChord.symDown] state is side-precise and sufficient.
 */
fun KeyEvent.toKeyPress(): KeyPress = KeyPress(
    keyCode = keyCode,
    unicodeChar = unicodeChar,
    metaState = metaState,
    repeatCount = repeatCount,
    fnHeld = isFunctionPressed || HardwareChord.isFnDown,
    symHeld = isSymPressed || HardwareChord.isSymDown,
)
