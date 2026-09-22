/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.shortcut.ShortcutAction
import org.fcitx.fcitx5.android.input.shortcut.ShortcutChord

/**
 * Predefined sets ("profiles") of hardware keyboard key bindings.
 *
 * Each profile maps a [AppPrefs.HardwareKeyboard] key-binding preference key to its fcitx5
 * portableString value (e.g. "Alt+space", "dollar", "Shift_L") or to a [HardwareSpecialKeys]
 * pseudo-key name (e.g. "Sym", "NavBack") for physical function keys that have no fcitx5 KeySym.
 * Selecting a profile in the settings screen overwrites every individual key binding with the
 * profile's values.
 *
 * [BLACKBERRY] is the original/default set of bindings. [TT2], [TITAN2_ELITE] and
 * [TITAN2_ELITE_MOD] are alternative layouts. [TITAN2_ELITE_MOD] is [TITAN2_ELITE] with the
 * candidate-selection and paging keys remapped (see the operation manual).
 * The individual keys remain editable afterwards, so a profile is only an initial batch set.
 */
object HardwareKeyProfiles {

    const val BLACKBERRY = "blackberry"
    const val TT2 = "tt2"
    const val TITAN2_ELITE = "titan2_elite"
    const val TITAN2_ELITE_MOD = "titan2_elite_mod"

    /** All available profile ids, in display order. */
    fun ids(): List<String> = listOf(BLACKBERRY, TT2, TITAN2_ELITE, TITAN2_ELITE_MOD)

    /**
     * 预设显示名。下拉框的 entries 与「恢复推荐键位」的摘要都从这里取，
     * 不各写一份 id → 名字的映射（同一个映射抄两份必然漂移）。
     */
    @StringRes
    fun labelResFor(name: String): Int = when (name) {
        TT2 -> R.string.hw_profile_tt2
        TITAN2_ELITE -> R.string.hw_profile_titan2_elite
        TITAN2_ELITE_MOD -> R.string.hw_profile_titan2_elite_mod
        else -> R.string.hw_profile_blackberry
    }

    /**
     * Single source of truth for the 11 hardware-keyboard key-binding preferences, in canonical
     * order. The value lists ([blackberryValues] / [tt2Values]) are defined in the same order, so
     * every profile implementation (`get` / `applyProfile` / `candidateKeys`) is built by
     * `keyBindings(hw) zip values`, eliminating the duplicate `hw.xxx.key` listings that
     * previously appeared in `blackberry` / `tt2` / `applyProfile` and could drift apart.
     */
    private fun keyBindings(hw: AppPrefs.HardwareKeyboard): List<ManagedPreference.PString> = listOf(
        hw.candidate1Key,
        hw.candidate2Key,
        hw.candidate3Key,
        hw.candidate4Key,
        hw.candidate5Key,
        hw.pageNextKey,
        hw.pagePrevKey,
        hw.symbolPickerKey,
        hw.toggleImeKey,
        hw.pickerKey,
        hw.altLatchKey,
    )

    /**
     * 黑莓（Q25）默认键位。
     *
     * ⚠️ SYM 按钮与右 Alt（`KEYCODE_ALT_RIGHT`）是**两个独立的物理键**，识别上不混用（见
     * [HardwareSpecialKeys] 的 `Sym` 条目：那里已不含 `KEYCODE_ALT_RIGHT`）。这里把符号窗口键绑成
     * `"Alt_R"`（fcitx5 的 Alt_R keysym）=「右 Alt 开符号窗口」，走 keysym 路径匹配，与 `Sym`
     * 伪键（只匹配 `KEYCODE_SYM` / `KEYCODE_PICTSYMBOLS`）互不干扰。若想在真机用独立的 SYM 按钮
     * 开符号窗口，把该值改成 `"Sym"` 即可。
     */
    private val blackberryValues = listOf(
        "space", "0", "Alt_R", "Shift_L", "Shift_R",
        "grave", "Alt+grave", "Alt_R", "Alt+space", "Shift+space", "Alt_L",
    )

    private val tt2Values = listOf(
        "space", "Control_L", "Tab", "Shift_L", "Alt_R",
        "", "", "", "Alt+space", "Shift+space", "Alt_R",
    )

    /**
     * Titan2 Elite. Its bottom row (`TitanKey.kl` ROW4) is
     * `左Shift / 返回 / home / 空格 / 后台任务 / fn / 右Shift`, but **home and 后台任务 cannot be
     * bound at all**: they report `KEYCODE_HOME` / `KEYCODE_APP_SWITCH`, which the window policy
     * consumes before any window — including the IME window — so no input method ever receives them
     * (measured: pressing either produces zero events on the IME side, while their keyCodes are
     * already present in the shortcut tables).
     *
     * What remains is exactly five bottom-row keys, symmetric around 空格, so the full 巨硬
     * (4-2-1-3-5) row still works: `左Shift=4th, 返回=2nd, 空格=1st (centre), fn=3rd, 右Shift=5th`.
     *
     * Paging therefore moves off the Shifts (they are candidate keys now) onto DPAD left/right —
     * pending a measurement of whether DPAD events reach the input method on this keyboard.
     * `altLatchKey` is a bare `Alt_L` because this keyboard has no right Alt (only `KEY_LEFTALT`).
     */
    private val titan2EliteValues = listOf(
        "space",        // candidate1Key     空格 —— 巨硬首选字（居中）
        "NavBack",      // candidate2Key     返回
        "NavFn",        // candidate3Key     fn
        "Shift_L",      // candidate4Key     左Shift
        "Shift_R",      // candidate5Key     右Shift
        "Sym",        // pageNextKey       
        "Alt+Sym",         // pagePrevKey       
        "NavFn",          // symbolPickerKey   SYM 键
        "Alt+space",    // toggleImeKey
        "Shift+space",  // pickerKey
        "Alt_L",        // altLatchKey       本机只有左 Alt
    )

    /**
     * Titan2 Elite（改键）. 与 [titan2EliteValues] 同硬件、同符号窗口/Fn/Alt 这套，只是把
     * 「选字」和「翻页」的键位重排（对应操作手册里的「Titan2 Elite（改键后）」布局）：
     *
     *  - 巨硬选字五键改为 `0 | 返回 | 空格 | Ctrl | Fn`（原 `左Shift | 返回 | 空格 | Fn | 右Shift`）。
     *    其中 `0` 与 `Ctrl` 来自用户在系统设置里把 ⭕️Home、`⬛️`多任务 两个被窗口策略吃掉的键
     *    分别重映射成 `0` / `Ctrl` —— 这两个键 IME 收不到，不重映射就空着。
     *  - 翻页从 `Sym` / `Alt+Sym` 改到 `右Shift`(下一页) / `左Shift`(上一页)，把原本占着选字位的
     *    左右 Shift 让出来给选字。
     *
     * `Ctrl` 用 `Control_L`：系统重映射一般发 `KEYCODE_CTRL_LEFT`（→ XK_Control_L）。若你的重映射器
     * 发的是 `KEYCODE_CTRL_RIGHT`，把这一行改成 `"Control_R"` 即可。
     */
    private val titan2EliteModValues = listOf(
        "space",        // candidate1Key     空格 —— 巨硬首选字（居中）
        "NavBack",      // candidate2Key     返回
        "Control_L",    // candidate3Key     Ctrl（系统重映射 ⬛️ 多任务键为 Ctrl 后）
        "0",            // candidate4Key     0（系统重映射 ⭕️ Home 键为 0 后）
        "NavFn",        // candidate5Key     Fn
        "Shift_R",      // pageNextKey       右 Shift（下一页）
        "Shift_L",      // pagePrevKey       左 Shift（上一页）
        "NavFn",        // symbolPickerKey   Fn 键（轻按开符号窗口）
        "Alt+space",    // toggleImeKey
        "Shift+space",  // pickerKey
        "Alt_L",        // altLatchKey       本机只有左 Alt
    )

    private fun valuesFor(name: String): List<String> = when (name) {
        TT2 -> tt2Values
        TITAN2_ELITE -> titan2EliteValues
        TITAN2_ELITE_MOD -> titan2EliteModValues
        else -> blackberryValues
    }

    /**
     * 「快捷键」动作键的字母。
     *
     * Titan 系两族各挂一个伪修饰键（Fn / Sym，前缀不同=不同手势，字母本可重叠）；但**黑莓把两族
     * 统一挂到 `Alt_R`** 上（见 [shortcutValuesFor]），同一个修饰键下字母撞了就真的抢键，
     * 所以这里**全局唯一**：
     *  - 编辑类（Fn/Alt_R）：A/C/X/V/Q/Z 编辑、S/F/E/D 光标簇、U/J/H/K 选字簇（U=上 J=下 H=左 K=右）；
     *  - 开关类（Sym/Alt_R）：拼音助记 特效 t、音 y、排列 p、fLy l、Bar b、Mode m。
     *
     * `when` 显式穷举 [ShortcutAction]：以后加动作若忘了给字母会直接编译不过，
     * 不会静默漏一个（「加了枚举项却没有绑定」正是本项目最怕的那类静默失效）。
     * ⚠️ 新字母必须先核对与上表 20 个字母不重 —— 黑莓下没有前缀可以救你。
     */
    private fun leaderFor(action: ShortcutAction): String = when (action) {
        ShortcutAction.ToggleEffects -> "t"
        ShortcutAction.ToggleSound -> "y"
        ShortcutAction.ToggleStatusBar -> "b"
        ShortcutAction.ToggleFlyText -> "l"
        ShortcutAction.ToggleArrangement -> "p"
        ShortcutAction.CycleSoundMode -> "m"
        ShortcutAction.SelectAll -> "a"
        ShortcutAction.Copy -> "c"
        ShortcutAction.Cut -> "x"
        ShortcutAction.Paste -> "v"
        ShortcutAction.ClearAll -> "q"
        ShortcutAction.Undo -> "z"
        ShortcutAction.CursorLeft -> "s"
        ShortcutAction.CursorRight -> "f"
        ShortcutAction.CursorUp -> "e"
        ShortcutAction.CursorDown -> "d"
        ShortcutAction.SelectLeft -> "h"
        ShortcutAction.SelectRight -> "k"
        ShortcutAction.SelectUp -> "u"
        ShortcutAction.SelectDown -> "j"
    }

    /**
     * 该预设下的推荐动作键（[ShortcutAction] → 绑定串）。
     *
     * Titan 系（tt2 / titan2_elite / titan2_elite_mod）按动作家族分两套伪修饰键
     * （归属见 [ShortcutAction.chord]）：编辑类 `Fn+字母`、开关类 `Sym+字母`。
     * **黑莓**没有空闲伪修饰键（左 Alt 是键帽符号，右 Alt 已绑符号窗口），两族统一挂
     * `Alt_R+字母` —— 真修饰键侧别精确（[HardwareChord.ALT_R] 读 META_ALT_RIGHT_ON），
     * 左 Alt 的键帽符号输入不受影响。因为黑莓两族共用同一个修饰键，
     * [leaderFor] 的字母必须**全局唯一**。
     *
     * ⚠️ 黑莓的 `Alt_R` 裸键绑着符号窗口（symbolPickerKey）与候选 3（candidate3Key）：
     * 按住 Alt_R 和弦时，裸键按下那一下会先触发对应绑定（按与按住+字母是可区分的手势）。
     *
     * 默认值全是「按住修饰键 + 字母」这种组合，避开所有裸键：裸键（字母）本来就要打字。
     */
    fun shortcutValuesFor(name: String): Map<ShortcutAction, String> {
        if (name == BLACKBERRY) {
            return ShortcutAction.entries.associateWith {
                HardwareChord.compose(HardwareChord.ALT_R, leaderFor(it))
            }
        }
        return ShortcutAction.entries.associateWith {
            HardwareChord.compose(
                when (it.chord) {
                    ShortcutChord.FN -> HardwareChord.FN
                    ShortcutChord.SYM -> HardwareChord.SYM
                },
                leaderFor(it)
            )
        }
    }

    /** 把该预设的推荐动作键写进 `AppPrefs.Shortcuts`。 */
    fun applyShortcutPreset(name: String, prefs: AppPrefs) {
        val shortcuts = prefs.shortcuts
        shortcutValuesFor(name).forEach { (action, value) -> shortcuts.key(action).setValue(value) }
    }

    /** Resolve the key-binding map for the given profile id (defaults to [BLACKBERRY]). */
    fun get(name: String, hw: AppPrefs.HardwareKeyboard): Map<String, String> =
        keyBindings(hw).zip(valuesFor(name)).associate { (pref, value) -> pref.key to value }

    /**
     * Overwrite every individual key-binding preference with the values of the given profile,
     * **and seed the matching shortcut set**.
     *
     * Centralised here so the settings screen and the first-run initialiser share one code path
     * — the profile list is the single source of truth, eliminating the class of bug where a
     * key's factory default drifted out of sync with the preset (which left the next-page key
     * dead on a fresh install until a preset was re-selected).
     *
     * 快捷键一起播是刻意的：修饰键随机器不同（Fn / Alt），只播物理键位会留下「有键位、
     * 没动作键」的半套配置。用户自定的动作键会被这次切换覆盖 —— 与物理键位同一个取舍。
     */
    fun applyProfile(name: String, prefs: AppPrefs) {
        val hw = prefs.hardwareKeyboard
        keyBindings(hw).zip(valuesFor(name)).forEach { (pref, value) -> pref.setValue(value) }
        applyShortcutPreset(name, prefs)
    }

    /**
     * Single source of truth for the four "candidate2-5" preference references. Used by
     * [candidateKeys] (to seed profile defaults), [clearCandidateKeys] (to wipe them when
     * switching to 巨硬 → 普通), and the settings UI to identify the four rows whose
     * visibility is driven by the candidate display mode.
     */
    private fun candidate2to5(hw: AppPrefs.HardwareKeyboard): List<ManagedPreference.PString> = listOf(
        hw.candidate2Key,
        hw.candidate3Key,
        hw.candidate4Key,
        hw.candidate5Key,
    )

    /**
     * Returns only the candidate2-5 (preference, value) pairs for the given profile. Values are
     * looked up by the same [valuesFor] list that [applyProfile] uses, so this method never
     * redefines a key string and there is exactly one place to update per profile.
     *
     * Used by the "候选显示模式 = 巨硬" re-entry path to re-seed the four physical selection keys
     * after a round trip through "普通" (which clears them) — without touching
     * [AppPrefs.HardwareKeyboard.candidate1Key] (first-pick, stays bound to Space) or the
     * paging/symbol/global keys.
     */
    fun candidateKeys(name: String, hw: AppPrefs.HardwareKeyboard): List<Pair<ManagedPreference.PString, String>> {
        val bindings = keyBindings(hw)
        val values = valuesFor(name)
        return candidate2to5(hw).mapNotNull { pref ->
            bindings.indexOf(pref).takeIf { it >= 0 }?.let { pref to values[it] }
        }
    }

    /**
     * Apply only the candidate2-5 portion of the given profile. See [candidateKeys].
     */
    fun applyCandidateKeys(name: String, hw: AppPrefs.HardwareKeyboard) {
        candidateKeys(name, hw).forEach { (pref, value) -> pref.setValue(value) }
    }

    /**
     * Clear all four candidate2-5 preferences to empty strings — used by the
     * "候选显示模式 = 普通" entry path to disable the bottom-row physical-key quick-pick shortcuts
     * at runtime. Symmetric to [applyCandidateKeys] but writes "" instead of the profile values.
     */
    fun clearCandidateKeys(hw: AppPrefs.HardwareKeyboard) {
        candidate2to5(hw).forEach { it.setValue("") }
    }
}
