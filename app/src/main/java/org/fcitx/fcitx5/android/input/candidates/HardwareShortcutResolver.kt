/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareChord
import org.fcitx.fcitx5.android.data.prefs.HardwareSpecialKeys
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidateArrangementMode
import org.fcitx.fcitx5.android.input.shortcut.ShortcutAction
import org.fcitx.fcitx5.android.utils.normalizeKeyString

/**
 * View-independent resolution of physical-keyboard candidate shortcuts.
 *
 * The virtual keyboard's horizontal candidate bar (via
 * [org.fcitx.fcitx5.android.input.InputView]) and the physical-keyboard floating candidate
 * window ([org.fcitx.fcitx5.android.input.CandidatesView]) both need to map a pressed physical
 * key to a candidate position; they share key-string parsing, paging and memoization caches here
 * so neither view keeps a private copy of the shortcut logic.
 *
 * This facade takes [KeyPress] snapshots, never `android.view.KeyEvent`: callers capture the
 * snapshot once at dispatch entry ([toKeyPress]), and the matching rules live in
 * [HardwareKeyMatcher] — a pure module unit tests drive directly with hand-built snapshots.
 * The only step that cannot run on the JVM is [parseKeyString]'s `Key.parse` (JNI); bindings
 * round-trip through the Lab key-capture page on-device instead.
 *
 * The two surfaces differ in how a position is resolved:
 * - The horizontal bar is arrangement-aware: it uses a BlackBerry slot table and a centre
 *   first-pick (Macrohard) vs linear (Linear) layout, via [resolveShortcutPosition].
 * - The floating window renders candidates in plain index order, so selection is **strictly by
 *   sequence number** (candidate1 → 0, candidate2 → 1, …) via [resolveShortcutPositionBySequence],
 *   independent of arrangement mode.
 */
object HardwareShortcutResolver {

    internal sealed interface ParsedKey {
        /** A pseudo key with no fcitx5 KeySym — see [HardwareSpecialKeys]. */
        data class Special(val entry: HardwareSpecialKeys.Entry) : ParsedKey
        data class Ref(val key: Key) : ParsedKey

        /**
         * 伪修饰键和弦（`Fn+字母` / `Sym+字母`）。修饰键没有 fcitx5 Keysym、也进不了 metaState，
         * 所以只能靠 [KeyPress.fnHeld] / [KeyPress.symHeld] 在快照时刻冻结的按住状态来判 ——
         * 按住状态的唯一写方见 [HardwareChord]。
         */
        data class Chord(val modifier: String, val inner: ParsedKey) : ParsedKey
    }

    private val parsedKeyCache = mutableMapOf<String, ParsedKey>()
    private var preciseShortcutsCache =
        mutableMapOf<Pair<Int, CandidateArrangementMode>, List<ShortcutRule>>()
    private var wideShortcutsCache = mutableMapOf<CandidateArrangementMode, List<ShortcutRule>>()
    private var shortcutKeysCache: List<ParsedKey>? = null

    /** Alt-latch 触发键的 memo：字符串没变就不重复走 JNI parse（每次物理按键都会问一次）。 */
    private var latchKeyString: String? = null
    private var latchKeyParsed: ParsedKey? = null

    /** 动作快捷键（开关类…）的解析结果，按 [ShortcutAction] 声明顺序排列 = 匹配优先级。 */
    private var actionParsedKeysCache: List<Pair<ShortcutAction, ParsedKey>>? = null

    private data class ShortcutRule(val parsedKey: ParsedKey?, val position: Int)

    private val hardwareKeyboardPrefs = AppPrefs.getInstance().hardwareKeyboard
    private val shortcutsPrefs = AppPrefs.getInstance().shortcuts
    private val arrangementModePref = AppPrefs.getInstance().candidateBar.arrangementMode

    init {
        // Keep the memoized shortcut tables in sync with the user's key bindings.
        // BOTH categories must be watched: the action bindings live in [shortcutsPrefs], and
        // forgetting its listener is the classic "改了配置但快捷键还是老行为" bug.
        hardwareKeyboardPrefs.registerOnChangeListener { invalidateCaches() }
        shortcutsPrefs.registerOnChangeListener { invalidateCaches() }
    }

    fun invalidateCaches() {
        parsedKeyCache.clear()
        preciseShortcutsCache.clear()
        wideShortcutsCache.clear()
        shortcutKeysCache = null
        actionParsedKeysCache = null
        latchKeyString = null
        latchKeyParsed = null
    }

    // ------------------------------------------------------------------ parsing

    internal fun parseKeyString(keyString: String): ParsedKey? {
        if (keyString.isEmpty()) return null
        // 和弦先拆前缀，再按原来的路解析内层键。刻意放在 memoize 之前：内层键自己去缓存命中，
        // 这里多一层包装不值得进缓存（而且 getOrPut 的 value 类型非空，裂成两条路更好写）。
        val (modifier, inner) = HardwareChord.split(keyString)
        if (modifier.isNotEmpty()) {
            return parseKeyString(inner)?.let { ParsedKey.Chord(modifier, it) }
        }
        return parsedKeyCache.getOrPut(keyString) {
            // Pseudo keys MUST be looked up before Key.parse: their names deliberately avoid the
            // fcitx5 native key names (which "Back" / "Home" / "Fn" would otherwise collide with
            // and silently resolve to something else).
            HardwareSpecialKeys.entryForName(keyString)?.let { return@getOrPut ParsedKey.Special(it) }
            ParsedKey.Ref(Key.parse(normalizeKeyString(keyString)))
        }
    }

    // ------------------------------------------------------------------ matching (thin delegates to HardwareKeyMatcher)

    internal fun matchesParsedKey(kp: KeyPress, parsed: ParsedKey?): Boolean =
        HardwareKeyMatcher.matchesParsedKey(kp, parsed)

    /** Match by KeySym only (any modifier state) — a physical key regardless of what is held. */
    internal fun matchesKeySymOnly(kp: KeyPress, parsed: ParsedKey): Boolean =
        HardwareKeyMatcher.matchesKeySymOnly(kp, parsed)

    fun isSameKeySymString(kp: KeyPress, keyString: String): Boolean {
        val parsed = parseKeyString(keyString) ?: return false
        return matchesKeySymOnly(kp, parsed)
    }

    /**
     * Whether [kp] matches any configured hardware shortcut key
     * (candidates / symbol / paging / global / action).
     */
    fun isHardwareShortcutKey(kp: KeyPress): Boolean =
        shortcutParsedKeys().any { matchesParsedKey(kp, it) }

    /**
     * Whether [kp] is the configured Alt-latch trigger key (setting:
     * hardwareKeyboard.altLatchKey, default "Alt_L"). An empty value disables latching.
     *
     * Matched by keysym only (bare physical press, residual modifiers ignored). This goes
     * through the SAME parse path as every other binding, so pseudo keys (`Sym`) and chord
     * prefixes (`Fn+x`) work here exactly as they do for candidate shortcuts — the Service's
     * old private copy consulted neither and silently ignored such bindings.
     */
    fun matchesLatchKey(kp: KeyPress): Boolean {
        val keyString = hardwareKeyboardPrefs.altLatchKey.getValue()
        if (keyString.isEmpty()) return false
        if (keyString != latchKeyString) {
            latchKeyString = keyString
            latchKeyParsed = parseKeyString(keyString)
        }
        val parsed = latchKeyParsed ?: return false
        return matchesKeySymOnly(kp, parsed)
    }

    /**
     * 解析 [kp] 命中的动作快捷键（开关类…），未绑定时返回 null。
     *
     * 调用方 (`FcitxInputMethodService.onKeyDown`) 把它放在整条派发链的**最前面**，
     * 于是同一个键既被绑成候选字又被绑成动作时，动作赢——这条优先级是刻意的，配置界面
     * 会在保存时提示冲突，用户自己拍板。
     *
     * [ShortcutAction] 的声明顺序即优先级：同键多绑定时取最先命中者。
     */
    fun resolveAction(kp: KeyPress): ShortcutAction? =
        actionParsedKeys().firstOrNull { matchesParsedKey(kp, it.second) }?.first

    /**
     * 已绑定的动作键（跳过空串），与缓存同生共死。所有预设（含黑莓）统一放行：
     * 黑莓现在也提供动作快捷键（统一挂 `Alt_R`，见 HardwareKeyProfiles.shortcutValuesFor），
     * 不再有「幽灵绑定」门控 —— 预设切换会整批重写偏好，历史值随「恢复推荐键位」清掉。
     */
    private fun actionParsedKeys(): List<Pair<ShortcutAction, ParsedKey>> {
        actionParsedKeysCache?.let { return it }
        val keys = ShortcutAction.entries.mapNotNull { action ->
            parseKeyString(shortcutsPrefs.key(action).getValue())?.let { action to it }
        }
        actionParsedKeysCache = keys
        return keys
    }

    private fun shortcutParsedKeys(): List<ParsedKey> {
        shortcutKeysCache?.let { return it }
        val hw = hardwareKeyboardPrefs
        val keys = listOf(
            hw.candidate1Key, hw.candidate2Key, hw.candidate3Key, hw.candidate4Key, hw.candidate5Key,
            hw.symbolPickerKey, hw.pageNextKey, hw.pagePrevKey, hw.toggleImeKey, hw.pickerKey,
        ).mapNotNull { parseKeyString(it.getValue()) } + actionParsedKeys().map { it.second }
        shortcutKeysCache = keys
        return keys
    }

    // ------------------------------------------------------------------ paging (single source; used to be copied 3×)

    /**
     * Resolve paging direction from [kp]. Returns -1 (previous page), 1 (next page),
     * or null when the press does not match either paging key.
     *
     * 唯一的翻页仲裁。水平候选条、picker 窗口、浮动候选窗曾各持一份逐字同构的拷贝
     * （"combo 优先于同物理键的纯键绑定"抄了三遍），现在三个消费方都只问这里。
     */
    fun resolvePaging(kp: KeyPress): Int? {
        val hw = hardwareKeyboardPrefs
        return HardwareKeyMatcher.pagingDirection(
            kp,
            nextParsed = parseKeyString(hw.pageNextKey.getValue()),
            prevParsed = parseKeyString(hw.pagePrevKey.getValue()),
        )
    }

    // ------------------------------------------------------------------ position tables

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
     * Resolve the visible candidate position selected by [kp] for a surface showing [count]
     * candidates, or null when no candidate shortcut key matches.
     */
    fun resolveShortcutPosition(kp: KeyPress, count: Int): Int? {
        preciseShortcuts(count)?.let { rules ->
            for (r in rules) if (matchesParsedKey(kp, r.parsedKey)) return r.position
        }
        if (count > CandidateUi.BlackBerryBottomRowKeyCount) {
            for (r in wideShortcuts()) {
                if (matchesParsedKey(kp, r.parsedKey) && r.position < count) return r.position
            }
        }
        return null
    }

    /**
     * Resolve the candidate position selected by [kp] strictly by **sequence number**, ignoring
     * any layout / arrangement mode (BlackBerry slots, centre first-pick). Used by the floating
     * candidate window ([org.fcitx.fcitx5.android.input.CandidatesView]), which renders candidates
     * in plain index order, so candidate1 → 0, candidate2 → 1, … candidate5 → 4.
     *
     * A candidate key bound to a combo (e.g. "Alt+space") still maps to its sequence position,
     * because [matchesParsedKey] honours the modifier exactly.
     */
    fun resolveShortcutPositionBySequence(kp: KeyPress, count: Int): Int? {
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
            if (matchesParsedKey(kp, parsed) && pos < count) return pos
        }
        return null
    }
}
