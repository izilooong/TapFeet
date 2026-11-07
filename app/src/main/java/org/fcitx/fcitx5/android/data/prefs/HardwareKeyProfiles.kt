/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.input.shortcut.ShortcutAction
import org.fcitx.fcitx5.android.input.shortcut.ShortcutChord

/**
 * 键盘预设的门面：静态事实全部在 [HardwareKeyProfile]，本对象只负责「按预设写偏好」的副作用与
 * 供 UI 取用的派生 adapter。
 *
 * 每个预设把 11 个 [AppPrefs.HardwareKeyboard] 键位偏好写成 fcitx5 portableString
 * （如 "Alt+space" / "Shift_L"）或 [HardwareSpecialKeys] 伪键名（如 "Sym" / "NavBack"）。
 * 选中预设会覆盖全部单个键位；之后用户仍可逐项编辑，所以预设只是初始批量值。
 *
 * 保留吃 String 的 public API，是为了让 `hw_key_profile` 的持久化值与 UI 下拉的 entryValues
 * 保持原样；String → 预设的解析只在 [HardwareKeyProfile.from] 一处发生。
 */
object HardwareKeyProfiles {

    /** All available profile ids, in display order. */
    fun ids(): List<String> = HardwareKeyProfile.entries.map { it.id }

    /**
     * 预设显示名。下拉框的 entries 与「恢复推荐键位」的摘要都从这里取，
     * 不各写一份 id → 名字的映射（同一个映射抄两份必然漂移）。
     */
    @StringRes
    fun labelResFor(name: String): Int = HardwareKeyProfile.from(name).labelRes

    /**
     * 11 个键位偏好的规范顺序。[HardwareKeyProfile.bindings] 按同一顺序给出值，
     * `applyProfile` / `candidateKeys` 都用 `keyBindings(hw) zip profile.bindings` 取用，
     * 不在别处再抄一份 `hw.xxx.key` 清单。
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

    // 11 个键位值由 HardwareKeyProfile 的 bindings 持有；门面不再存第二份。

    /**
     * 「快捷键」动作键的字母。
     *
     * Titan 系两族各挂一个伪修饰键（Fn / Sym，前缀不同=不同手势，字母本可重叠）；但**黑莓把两族
     * 统一挂到 `Shift_R`** 上（见 [shortcutValuesFor]），同一个修饰键下字母撞了就真的抢键，
     * 所以这里**全局唯一**：
     *  - 编辑类（Fn/Alt_R）：A/C/X/V/Q/Z 编辑、S/F/E/D 光标簇、U/J/H/K 选字簇（U=上 J=下 H=左 K=右）；
     *  - 开关类（Sym/Alt_R）：拼音助记 特效 t、音 y、排列 p、fLy l、Bar b、Mode m、录音 r。
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
        ShortcutAction.VoiceInput -> "r"
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
     * **黑莓**没有空闲伪修饰键（左 Alt 是键帽符号，Alt_R 已绑符号窗口/候选 3，Alt_R 占用太多），
     * 两族统一挂 `Shift_R+字母` —— 真修饰键侧别精确（[HardwareChord.SHIFT_R] 读
     * META_SHIFT_RIGHT_ON），左 Shift（候选 4）与打字不受影响。因为黑莓两族共用同一个修饰键，
     * [leaderFor] 的字母必须**全局唯一**。
     *
     * ⚠️ 黑莓的 `Shift_R` 裸键绑着候选 5（candidate5Key）：按住 Shift_R 和弦时，裸键按下那一下
     * 会先触发候选 5（按与按住+字母是可区分的手势）。
     *
     * 默认值全是「按住修饰键 + 字母」这种组合，避开所有裸键：裸键（字母）本来就要打字。
     */
    fun shortcutValuesFor(name: String): Map<ShortcutAction, String> =
        valuesForScheme(HardwareKeyProfile.from(name).shortcutScheme)

    /** 对 [ShortcutScheme] 穷尽分派：新增方案漏给分支会直接编译不过，不再有隐含兜底。 */
    private fun valuesForScheme(scheme: ShortcutScheme): Map<ShortcutAction, String> = when (scheme) {
        ShortcutScheme.BLACKBERRY -> ShortcutAction.entries.associateWith {
            HardwareChord.compose(HardwareChord.SHIFT_R, leaderFor(it))
        }
        // Passport：本机没有 Shift/Alt/Fn/Sym 任何修饰键，26 字母全部要留给打字，
        // 触摸面四向键已绑给翻页 —— 动作快捷键无键可播，整套留空。
        ShortcutScheme.PASSPORT -> ShortcutAction.entries.associateWith { "" }
        ShortcutScheme.TITAN -> ShortcutAction.entries.associateWith {
            HardwareChord.compose(
                when (it.chord) {
                    ShortcutChord.FN -> HardwareChord.FN
                    ShortcutChord.SYM -> HardwareChord.SYM
                },
                leaderFor(it)
            )
        }
    }

    /**
     * 自定义键盘是否「固定显示」：Passport 预设专属。该机的 29 键物理键盘没有任何符号/数字，
     * 自定义一行键盘就是它的符号排 —— 主键盘布局直接钉死到自定义键盘（见
     * [org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow.doSwitchLayout] 的路由），
     * 该预设下快捷窗口面板循环剔除自定义态（已常驻，循环到它没有意义 —— 见
     * [org.fcitx.fcitx5.android.input.InputView.panelModuleEnabled]）；其它预设仍按总开关参与循环。
     *
     * ⚠️ **只由预设的 `customKeyboardPinned` 决定，不再依赖自定义键盘总开关（`customKeyboard.enabled`）。**
     * 该开关默认 `false`、且可被用户手滑关掉；一旦它为 false，passport 这台没有任何符号键
     * 的设备会直接废掉（键盘面一片空白）。所以 passport 钉死自定义键盘是**无条件**的，
     * 总开关对 passport 不再构成逃生口（想退回主键盘请直接切换预设）。
     * [applyProfile] 仍会把开关一并播成开，仅为让设置页开关显示「开」、不负载行为。
     */
    fun customKeyboardPinned(): Boolean = HardwareKeyProfile
        .from(AppPrefs.getInstance().hardwareKeyboard.keyProfile.getValue())
        .customKeyboardPinned

    /** 把该预设的推荐动作键写进 `AppPrefs.Shortcuts`。 */
    fun applyShortcutPreset(name: String, prefs: AppPrefs) {
        val shortcuts = prefs.shortcuts
        shortcutValuesFor(name).forEach { (action, value) -> shortcuts.key(action).setValue(value) }
    }

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
        val profile = HardwareKeyProfile.from(name)
        val hw = prefs.hardwareKeyboard
        keyBindings(hw).zip(profile.bindings).forEach { (pref, value) -> pref.setValue(value) }
        applyShortcutPreset(name, prefs)
        // Passport 的自定义键盘是主键盘本体（固定显示）—— 切到该预设时把总开关一并播成开，
        // 否则 [customKeyboardPinned] 条件不成立、键盘面一片空白。切走预设不反向关闭：
        // 用户可能本来就手动开着自定义键盘，替他关掉是越权。
        if (profile.customKeyboardPinned) prefs.customKeyboard.enabled.setValue(true)
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
     * looked up by the same [HardwareKeyProfile.bindings] list that [applyProfile] uses, so this
     * method never redefines a key string and there is exactly one place to update per profile.
     *
     * Used by the "候选显示模式 = 巨硬" re-entry path to re-seed the four physical selection keys
     * after a round trip through "普通" (which clears them) — without touching
     * [AppPrefs.HardwareKeyboard.candidate1Key] (first-pick, stays bound to Space) or the
     * paging/symbol/global keys.
     */
    fun candidateKeys(name: String, hw: AppPrefs.HardwareKeyboard): List<Pair<ManagedPreference.PString, String>> {
        val bindings = keyBindings(hw)
        val values = HardwareKeyProfile.from(name).bindings
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
