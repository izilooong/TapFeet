/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.view.KeyEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidateArrangementMode
import org.fcitx.fcitx5.android.utils.normalizeKeyString

/**
 * View-independent resolution of physical-keyboard candidate shortcuts.
 *
 * The virtual keyboard's horizontal candidate bar ([org.fcitx.fcitx5.android.input.InputView])
 * and the physical-keyboard floating candidate window
 * ([org.fcitx.fcitx5.android.input.CandidatesView]) both need to map a pressed physical key to a
 * candidate position, and both share the key-string parsing, paging and memoization caches here
 * so the two views don't each duplicate ~150 lines of shortcut logic.
 *
 * The two surfaces differ in how a position is resolved:
 * - The horizontal bar is arrangement-aware: it uses a BlackBerry slot table and a centre
 *   first-pick (Macrohard) vs linear (Linear) layout, via [resolveShortcutPosition].
 * - The floating window renders candidates in plain index order, so selection is **strictly by
 *   sequence number** (candidate1 → 0, candidate2 → 1, …) via [resolveShortcutPositionBySequence],
 *   independent of arrangement mode.
 *
 * This object is intentionally free of any Android `View` state: callers supply the candidate
 * count and perform the final selection.
 */
object HardwareShortcutResolver {

    private sealed interface ParsedKey {
        object Sym : ParsedKey
        data class Ref(val key: Key) : ParsedKey
    }

    private val parsedKeyCache = mutableMapOf<String, ParsedKey>()
    private var preciseShortcutsCache =
        mutableMapOf<Pair<Int, CandidateArrangementMode>, List<ShortcutRule>>()
    private var wideShortcutsCache = mutableMapOf<CandidateArrangementMode, List<ShortcutRule>>()
    private var shortcutKeysCache: List<ParsedKey>? = null

    private data class ShortcutRule(val parsedKey: ParsedKey?, val position: Int)

    private val hardwareKeyboardPrefs = AppPrefs.getInstance().hardwareKeyboard
    private val arrangementModePref = AppPrefs.getInstance().candidateBar.arrangementMode

    init {
        // Keep the memoized shortcut tables in sync with the user's key bindings.
        hardwareKeyboardPrefs.registerOnChangeListener { invalidateCaches() }
    }

    fun invalidateCaches() {
        parsedKeyCache.clear()
        preciseShortcutsCache.clear()
        wideShortcutsCache.clear()
        shortcutKeysCache = null
    }

    private fun parseKeyString(keyString: String): ParsedKey? {
        if (keyString.isEmpty()) return null
        return parsedKeyCache.getOrPut(keyString) {
            if (keyString == "Sym") ParsedKey.Sym
            else ParsedKey.Ref(Key.parse(normalizeKeyString(keyString)))
        }
    }

    private fun matchesParsedKey(event: KeyEvent, parsed: ParsedKey?): Boolean {
        if (parsed == null) return false
        return when (parsed) {
            ParsedKey.Sym -> event.keyCode == KeyEvent.KEYCODE_SYM ||
                event.keyCode == KeyEvent.KEYCODE_PICTSYMBOLS
            is ParsedKey.Ref -> matchesKey(event, parsed.key)
        }
    }

    private fun isModifierKeySym(sym: Int): Boolean = sym in 0xffe1..0xffee

    private fun rawModifierStates(event: KeyEvent): KeyStates {
        var s = KeyState.NoState.state
        if (event.isAltPressed) s = s or KeyState.Alt.state
        if (event.isCtrlPressed) s = s or KeyState.Ctrl.state
        if (event.isShiftPressed) s = s or KeyState.Shift.state
        if (event.isMetaPressed) s = s or KeyState.Meta.state
        return KeyStates(s and KeyState.SimpleMask.state)
    }

    private fun matchesKey(event: KeyEvent, key: Key): Boolean {
        if (key.sym == 0) return false
        // Match by the physical key's keysym OR the character it produces. We must also accept the
        // keyCode-derived keysym because holding a modifier (e.g. Alt) can change event.unicodeChar
        // into a composed character, which would otherwise make the sym comparison fail for symbol
        // keys like grave (`) and break combos such as "Alt+grave". Character keys whose keyCode is
        // unreliable across layouts (e.g. `$`) still match via event.unicodeChar.
        val symFromKeyCode = FcitxKeyMapping.keyCodeToSym(event.keyCode)
        val symMatches = symFromKeyCode == key.sym ||
            (event.unicodeChar != 0 && event.unicodeChar == key.sym)
        if (!symMatches) return false
        if (isModifierKeySym(key.sym)) return true
        // A configured COMBO (has modifier, e.g. "Alt+grave") must match the modifier exactly, so use
        // raw states (no stripping). A plain key (no modifier) keeps [KeyStates.fromKeyEvent]'s
        // tolerant stripping, so an Alt-latched press of a number/symbol key still selects the
        // candidate (the original fcitx5-android behaviour).
        // A configured COMBO (has modifier, e.g. "Alt+grave") must match the modifier exactly, so
        // use raw states (no stripping). A plain key (no modifier) must ignore the system's residual
        // modifier state — notably Alt sticky/locked left by some ROMs after an Alt tap — so the
        // shortcut still works in editors where that happens. KeyStates.fromKeyEvent does this
        // clearing for number/symbol keys but *skips* the space key (it special-cases unicode == ' '),
        // which is exactly why the first-pick (Space) candidate shortcut failed in some editors while
        // candidate keys 2-5 kept working. Use an empty state directly so any plain key, Space
        // included, matches regardless of leftover Alt.
        val states = if (key.states != 0) rawModifierStates(event) else KeyStates.Empty
        return states.toInt() == key.states
    }

    private fun isSameKeySymString(event: KeyEvent, keyString: String): Boolean {
        val parsed = parseKeyString(keyString) ?: return false
        return when (parsed) {
            ParsedKey.Sym -> event.keyCode == KeyEvent.KEYCODE_SYM ||
                event.keyCode == KeyEvent.KEYCODE_PICTSYMBOLS
            is ParsedKey.Ref -> FcitxKeyMapping.keyCodeToSym(event.keyCode) == parsed.key.sym ||
                (event.unicodeChar != 0 && event.unicodeChar == parsed.key.sym)
        }
    }

    /** Whether [event] matches any configured hardware shortcut key (candidates / symbol / paging / global). */
    fun isHardwareShortcutKey(event: KeyEvent): Boolean {
        return shortcutParsedKeys().any { matchesParsedKey(event, it) }
    }

    private fun shortcutParsedKeys(): List<ParsedKey> {
        shortcutKeysCache?.let { return it }
        val hw = hardwareKeyboardPrefs
        val keys = listOf(
            hw.candidate1Key, hw.candidate2Key, hw.candidate3Key, hw.candidate4Key, hw.candidate5Key,
            hw.symbolPickerKey, hw.pageNextKey, hw.pagePrevKey, hw.toggleImeKey, hw.pickerKey,
        ).mapNotNull { parseKeyString(it.getValue()) }
        shortcutKeysCache = keys
        return keys
    }

    private fun candidate1Parsed(): ParsedKey? = parseKeyString(hardwareKeyboardPrefs.candidate1Key.getValue())

    fun candidate1HasModifier(): Boolean = (candidate1Parsed() as? ParsedKey.Ref)?.key?.states != 0

    /** candidate1 bound to a combo (e.g. "Alt+space") pressed → caller should select the centre/first-pick. */
    fun matchesCandidate1WithModifier(event: KeyEvent): Boolean {
        val parsed = candidate1Parsed() ?: return false
        return (parsed as? ParsedKey.Ref)?.key?.states != 0 && matchesParsedKey(event, parsed)
    }

    /** Plain candidate1 (no modifier) pressed → caller should select the first-pick candidate. */
    fun matchesCandidate1Plain(event: KeyEvent): Boolean {
        if (candidate1HasModifier()) return false
        return isSameKeySymString(event, hardwareKeyboardPrefs.candidate1Key.getValue())
    }

    /**
     * Resolve paging direction from [event]. Returns -1 (previous page), 1 (next page),
     * or null when the event does not match either paging key.
     */
    fun resolvePaging(event: KeyEvent): Int? {
        val hw = hardwareKeyboardPrefs
        val nextParsed = parseKeyString(hw.pageNextKey.getValue())
        val prevParsed = parseKeyString(hw.pagePrevKey.getValue())
        val nextMatches = matchesParsedKey(event, nextParsed)
        val prevMatches = matchesParsedKey(event, prevParsed)
        if (!nextMatches && !prevMatches) return null
        // A combo (modifier) binding takes precedence over a plain binding on the same physical key,
        // so e.g. "Alt+grave" (prev) is not stolen by a plain "grave" (next) binding.
        val prevHasModifier = (prevParsed as? ParsedKey.Ref)?.key?.states != 0
        val nextHasModifier = (nextParsed as? ParsedKey.Ref)?.key?.states != 0
        return when {
            prevMatches && prevHasModifier -> -1
            nextMatches && nextHasModifier -> 1
            prevMatches -> -1
            else -> 1
        }
    }

    /** Visible position of the "first-pick" candidate given the current candidate count. */
    fun firstPickPosition(count: Int): Int = when (arrangementModePref.getValue()) {
        CandidateArrangementMode.Macrohard -> (count - 1) / 2
        CandidateArrangementMode.Linear -> 0
    }

    // 1~5 候选的精细映射：物理键 → 可见位置。映射取决于候选栏排列模式（巨硬居中展开 / 普通线性），
    // 必须与 CandidateArrangementMode 保持一致，否则物理键会选到错误的候选。
    // candidate1(k1) 始终由调用方处理为"首选字"，不在此表内。
    private fun preciseShortcuts(count: Int): List<ShortcutRule>? {
        if (count <= 0 || count > 5) return null
        val arrangement = arrangementModePref.getValue()
        preciseShortcutsCache[count to arrangement]?.let { return it }
        val hw = hardwareKeyboardPrefs
        val rules = when (arrangement) {
            CandidateArrangementMode.Macrohard -> {
                // 巨硬：以"居中候选"为基准，左右物理键按相对偏移定位（候选数 2/3/4 时两侧键也能选到对应候选）
                val center = (count - 1) / 2
                mutableListOf<ShortcutRule>().apply {
                    (center - 1).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), it)) }
                    (center + 1).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), it)) }
                    (center - 2).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), it)) }
                    (center + 2).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), it)) }
                }
            }
            CandidateArrangementMode.Linear -> {
                // 普通：候选按 [1,2,3,4,5] 线性排布，物理键直接映射到顺序位置（candidate N → 位置 N-1）
                val keyFor = listOf(
                    hw.candidate2Key to 2,
                    hw.candidate3Key to 3,
                    hw.candidate4Key to 4,
                    hw.candidate5Key to 5
                )
                mutableListOf<ShortcutRule>().apply {
                    keyFor.forEach { (pref, n) ->
                        val pos = n - 1
                        if (pos < count) add(ShortcutRule(parseKeyString(pref.getValue()), pos))
                    }
                }
            }
        }
        preciseShortcutsCache[count to arrangement] = rules
        return rules
    }

    // >5 候选（wide layout）：物理键 → 可见位置，取决于排列模式。
    private fun wideShortcuts(): List<ShortcutRule> {
        val arrangement = arrangementModePref.getValue()
        wideShortcutsCache[arrangement]?.let { return it }
        val hw = hardwareKeyboardPrefs
        val rules = when (arrangement) {
            CandidateArrangementMode.Macrohard -> listOf(
                ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), CandidateUi.BlackBerryLeftSlot),
                ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), CandidateUi.BlackBerryInnerLeftSlot),
                ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), CandidateUi.BlackBerryInnerRightSlot),
                ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), CandidateUi.BlackBerryRightSlot),
            )
            CandidateArrangementMode.Linear -> listOf(
                ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), 1),
                ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), 2),
                ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), 3),
                ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), 4),
            )
        }
        wideShortcutsCache[arrangement] = rules
        return rules
    }

    /**
     * Resolve the visible candidate position selected by [event] for a surface showing [count]
     * candidates, or null when no candidate shortcut key matches.
     */
    fun resolveShortcutPosition(event: KeyEvent, count: Int): Int? {
        preciseShortcuts(count)?.let { rules ->
            for (r in rules) if (matchesParsedKey(event, r.parsedKey)) return r.position
        }
        if (count > CandidateUi.BlackBerryBottomRowKeyCount) {
            for (r in wideShortcuts()) {
                if (matchesParsedKey(event, r.parsedKey) && r.position < count) return r.position
            }
        }
        return null
    }

    /**
     * Resolve the candidate position selected by [event] strictly by **sequence number**, ignoring
     * any layout / arrangement mode (BlackBerry slots, centre first-pick). Used by the floating
     * candidate window ([org.fcitx.fcitx5.android.input.CandidatesView]), which renders candidates
     * in plain index order, so candidate1 → 0, candidate2 → 1, … candidate5 → 4.
     *
     * A candidate key bound to a combo (e.g. "Alt+space") still maps to its sequence position,
     * because [matchesParsedKey] honours the modifier exactly.
     */
    fun resolveShortcutPositionBySequence(event: KeyEvent, count: Int): Int? {
        if (count <= 0) return null
        val hw = hardwareKeyboardPrefs
        val rules = listOf(
            parseKeyString(hw.candidate1Key.getValue()) to 0,
            parseKeyString(hw.candidate2Key.getValue()) to 1,
            parseKeyString(hw.candidate3Key.getValue()) to 2,
            parseKeyString(hw.candidate4Key.getValue()) to 3,
            parseKeyString(hw.candidate5Key.getValue()) to 4,
        )
        for ((parsed, pos) in rules) {
            if (matchesParsedKey(event, parsed) && pos < count) return pos
        }
        return null
    }
}
