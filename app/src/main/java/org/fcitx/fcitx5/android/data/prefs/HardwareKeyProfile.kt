/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import timber.log.Timber

/** 动作快捷键的推荐键位挂在哪套修饰键上。 */
enum class ShortcutScheme {
    /** 两族统一挂 `Shift_R+字母`：真修饰键、侧别精确，字母必须全局唯一。 */
    BLACKBERRY,

    /** 按动作家族分挂 `Fn+字母` / `Sym+字母`。 */
    TITAN,

    /** 本机没有任何修饰键，整套动作键留空。 */
    PASSPORT,
}

/** 键帽符号表标识。多个预设可以指向同一张表（同硬件、同键帽）。 */
enum class KeycapSymbolMap {
    BLACKBERRY,
    TITAN2,
    TITAN2_ELITE,
    PASSPORT,
}

/**
 * 键盘预设：一台物理键盘机型的**全部静态事实**，收在唯一一处。
 *
 * 每个预设自带 [id]、显示名 [labelRes]、11 个键位值 [bindings]、动作快捷键方案
 * [shortcutScheme]、键帽符号表 [symbolMap] 与自定义键盘固定显示策略 [customKeyboardPinned]。
 * [bindings] 的顺序与 `HardwareKeyProfiles` 里 11 个键位偏好的规范顺序一一对应。
 *
 * 加一台机型 = 加一个 entry。所有取用方都以 `when (profile.xxx)` 对封闭集合穷尽分派，
 * 漏给某个事实会直接编译不过，不会再出现「键位落黑莓、动作键落 Titan」那类静默回落。
 *
 * [id] 同时是 SharedPreferences `hw_key_profile` 的持久化值，**不要改**：改了会让已装用户
 * 当前选中的预设失效。把预设写进偏好的副作用（`applyProfile` / `applyShortcutPreset`）不在这里，
 * 那属于配置生命周期，是另一个 seam。
 */
enum class HardwareKeyProfile(
    /** SharedPreferences `hw_key_profile` 的存储值，改动会让已装用户丢掉当前选择。 */
    val id: String,
    @StringRes val labelRes: Int,
    /** 11 个键位值，顺序与 11 个键位偏好一致：候选 1-5 / 下一页 / 上一页 / 符号窗 / 切换输入法 / 快捷窗口 / Alt Latch。 */
    val bindings: List<String>,
    val shortcutScheme: ShortcutScheme,
    val symbolMap: KeycapSymbolMap,
    val customKeyboardPinned: Boolean = false,
) {
    /**
     * BlackBerry（Q25）默认键位。
     *
     * SYM 按钮与右 Alt（`KEYCODE_ALT_RIGHT`）是两个独立物理键，识别上不混用；这里把符号窗键绑
     * `Alt_R`（fcitx5 keysym），与 `Sym` 伪键互不干扰。想在真机用独立 SYM 按钮开符号窗改成 `"Sym"`。
     */
    BLACKBERRY(
        id = "blackberry",
        labelRes = R.string.hw_profile_blackberry,
        bindings = listOf(
            "space", "0", "Alt_R", "Shift_L", "Shift_R",
            "grave", "Alt+grave", "Alt_R", "Alt+space", "Shift+space", "Alt_L",
        ),
        shortcutScheme = ShortcutScheme.BLACKBERRY,
        symbolMap = KeycapSymbolMap.BLACKBERRY,
    ),

    TT2(
        id = "tt2",
        labelRes = R.string.hw_profile_tt2,
        bindings = listOf(
            "space", "Control_L", "Tab", "Shift_L", "Alt_R",
            "", "", "", "Alt+space", "Shift+space", "Alt_R",
        ),
        shortcutScheme = ShortcutScheme.TITAN,
        symbolMap = KeycapSymbolMap.TITAN2,
    ),

    /**
     * Titan2 Elite。底排的 home / 后台任务 report `KEYCODE_HOME` / `KEYCODE_APP_SWITCH`，被窗口
     * 策略在 IME 窗口之前吃掉，无法绑定；剩下五键对称组成巨硬 4-2-1-3-5 行。翻页移到 Sym 键，
     * `altLatchKey` 用裸 `Alt_L`（本机没有右 Alt）。
     */
    TITAN2_ELITE(
        id = "titan2_elite",
        labelRes = R.string.hw_profile_titan2_elite,
        bindings = listOf(
            "space", "NavBack", "NavFn", "Shift_L", "Shift_R",
            "Sym", "Alt+Sym", "NavFn", "Alt+space", "Shift+space", "Alt_L",
        ),
        shortcutScheme = ShortcutScheme.TITAN,
        symbolMap = KeycapSymbolMap.TITAN2_ELITE,
    ),

    /**
     * Titan2 Elite（改键）：同硬件、同符号窗/Fn/Alt，只重排选字与翻页。巨硬五键改为
     * `0 | 返回 | 空格 | Ctrl | Fn`（0 / Ctrl 来自用户把被吃掉的两键在系统里重映射），
     * 翻页改到左右 Shift，把选字位让出来。`Ctrl` 用 `Control_L`，若重映射器发右 Ctrl 改 `Control_R`。
     */
    TITAN2_ELITE_MOD(
        id = "titan2_elite_mod",
        labelRes = R.string.hw_profile_titan2_elite_mod,
        bindings = listOf(
            "space", "NavBack", "Control_L", "0", "NavFn",
            "Shift_R", "Shift_L", "NavFn", "Alt+space", "Shift+space", "Alt_L",
        ),
        shortcutScheme = ShortcutScheme.TITAN,
        // 与 TITAN2_ELITE 同键帽，共用一张符号表；不要为了「一机一表」复制一份。
        symbolMap = KeycapSymbolMap.TITAN2_ELITE,
    ),

    /**
     * Passport（护照）：26 字母 + 空格 + 退格 + 回车共 29 键，**没有任何修饰键**，键帽无子刻印。
     * 触摸面把滑动翻译成 DPAD 四向（fcitx5 原生 keysym），直接当快捷键用。空格选首字，右滑下一页、
     * 左滑上一页，其余键位留空；符号窗走 KawaiiBar 按钮。自定义一行键盘是它的符号排，固定显示。
     */
    PASSPORT(
        id = "passport",
        labelRes = R.string.hw_profile_passport,
        bindings = listOf(
            "space", "", "", "", "",
            "Left", "Right", "", "", "", "",
        ),
        shortcutScheme = ShortcutScheme.PASSPORT,
        symbolMap = KeycapSymbolMap.PASSPORT,
        customKeyboardPinned = true,
    ),
    ;

    companion object {
        /**
         * 持久化 String → 预设的**唯一**解析入口。
         *
         * 未知值（旧版本残留 / 被清过的数据）统一落 [BLACKBERRY] 并留一条警告。所有下游都基于
         * 同一个 enum 派生，回落只发生在这一处，不再有各自为政的兜底目标。
         */
        fun from(id: String): HardwareKeyProfile =
            entries.firstOrNull { it.id == id } ?: run {
                Timber.w("Unknown key profile '%s'; falling back to %s", id, BLACKBERRY.id)
                BLACKBERRY
            }
    }
}
