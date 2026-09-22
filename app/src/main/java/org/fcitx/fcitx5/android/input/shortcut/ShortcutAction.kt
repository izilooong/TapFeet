/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/**
 * 动作的修饰键家族，决定推荐预设挂哪个修饰键。字符串真源是
 * [org.fcitx.fcitx5.android.data.prefs.HardwareChord] 的常量，这里不存串、只存归属。
 * Titan 系两族各挂一个（Fn / Sym）；黑莓两族统一挂 Alt_R（见 HardwareKeyProfiles），
 * 所以两族的字母必须**全局唯一**（见 HardwareKeyProfiles.leaderFor）。
 */
enum class ShortcutChord {
    /** 编辑类（含选字四向）：Titan 系 `Fn+字母`，黑莓 `Alt_R+字母`。 */
    FN,

    /** 开关类：Titan 系 `Sym+字母`，黑莓 `Alt_R+字母`。 */
    SYM,
}

/**
 * 可绑定物理键的动作（「快捷键」配置模块）。
 *
 * 一个动作 = 一条 `prefKey` 绑定 + 一个显示名 + 一个执行体。本枚举是**单一事实来源**：
 * 设置页按它逐行渲染、冲突检测按它取名、运行时按它逐条匹配，三处不再各维护一份列表。
 * 键名只在这里写一次（`AppPrefs.Shortcuts` 按 [prefKey] 生成偏好对象）——键名抄两份必然漂移，
 * 硬件键盘那批键位就栽过这个跟头（page-prev 曾默认成 "grave" 而非 "Alt+grave"，键失效）。
 *
 * **声明顺序 = 运行时匹配优先级**：同一个键绑到多个动作时取最先命中者，别随意调序。
 *
 * 偏好默认值一律为空串（不绑定），但**首次安装 / 切换键盘预设**时会按机型播一套推荐键位
 * （见 [org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles.shortcutValuesFor]），按 [chord]
 * 分两族：**编辑类**（含选字四向）写 `Fn+字母`、**开关类**写 `Sym+字母` —— Titan 系各挂一个伪
 * 修饰键；**黑莓（Q25）**没有空闲伪修饰键，两族统一挂 `Alt_R+字母`（真修饰键侧别精确，见
 * [org.fcitx.fcitx5.android.data.prefs.HardwareChord.ALT_R]），所以两族字母**全局唯一**。
 *
 * ⚠️ 黑莓的 `Alt_R` 裸键绑着符号窗口 / 候选 3：按住 Alt_R 和弦时，裸键按下那一下会先触发
 * 对应绑定（按与按住+字母是两个可区分的手势）。Alt Latch 注入无侧别的 META_ALT_ON，
 * 锁 Alt 状态下和弦不触发，左 Alt 键帽符号输入不受影响。
 *
 * 为什么不默认绑 Ctrl+C/V/A/Z 这类系统编辑键：Android 的 TextView 本来就支持它们，默认抢占等于
 * 遮蔽一条已经在工作的原生路径（WebView / Compose 下未必等价）。
 *
 * 执行体见 `InputView.performShortcutAction`；匹配见 `HardwareShortcutResolver.resolveAction`。
 */
enum class ShortcutAction(
    /** fcitx5 portableString 的存储键，见 `AppPrefs.Shortcuts`。 */
    val prefKey: String,
    @StringRes val titleRes: Int,
    /**
     * 动作家族，决定推荐预设挂哪个修饰键（[ShortcutChord]）。设置页的「Fn / Sym / Shift 快捷键」
     * 三个 Tab 也按它分组 —— 设置页不另写第二份分类判断。
     */
    val chord: ShortcutChord = ShortcutChord.SYM,
) {
    /** 输入特效总闸（`effects.enabled`）：粒子 / 飞字选字动画都归它管。 */
    ToggleEffects("shortcut_toggle_effects_key", R.string.shortcut_toggle_effects),

    /**
     * 物理键按键音（`hw_key_sound_enabled`）。同一个闸门也管键盘面手势音（上滑选字 / 左右翻页），
     * 因为两者共用 `FcitxInputMethodService.playHardwareSound` 那条管线——关掉会一起静音。
     */
    ToggleSound("shortcut_toggle_sound_key", R.string.shortcut_toggle_sound),

    /**
     * 空闲态顶部横条（KawaiiBar 的工具栏行）的隐藏开关。注意只在 `Idle` + 装饰子态才真的收起，
     * 候选栏 / 扩展窗标题态不收起（有意为之，否则会把候选栏或返回键藏掉）。
     */
    ToggleStatusBar("shortcut_toggle_statusbar_key", R.string.shortcut_toggle_statusbar),

    /** 键盘面滑动手势的飞字选字 / 翻页。仅键盘带触摸面的机型（Titan 2 Elite）有效。 */
    ToggleFlyText("shortcut_toggle_flytext_key", R.string.shortcut_toggle_flytext),

    /** 水平候选栏排列模式：巨硬（居中展开 4-2-1-3-5）↔ 普通（线性 1-2-3-4-5）。 */
    ToggleArrangement("shortcut_toggle_arrangement_key", R.string.shortcut_toggle_arrangement),

    /**
     * 虚拟键盘按键音的三态循环（`sound_on_keypress`）：跟随系统 → 启用 → 禁用 → 跟随系统。
     * 与 [ToggleSound] 是两个独立的闸门，不合并——二态开关表达不了三态，硬合会让"开"的含义变模糊。
     */
    CycleSoundMode("shortcut_cycle_sound_mode_key", R.string.shortcut_cycle_sound_mode),

    // —— 文本编辑类（chord=FN；作用在焦点编辑器上，经 InputConnection；
    // 编辑器自身给出可见反馈，不弹 Toast）——
    // 字母取自用户敲定的那套 `Fn+字母` 键位表（A/C/X/V/Q/Z + 光标簇 S/F/E/D + 选字簇 U/J/H/K），
    // 开关类那批挂在 Sym 前缀下，前缀不同=不同手势，字母重叠不算冲突。

    /** 全选（编辑器上下文菜单 SelectAll）。 */
    SelectAll("shortcut_select_all_key", R.string.shortcut_select_all, ShortcutChord.FN),

    /** 复制当前选区。 */
    Copy("shortcut_copy_key", R.string.shortcut_copy, ShortcutChord.FN),

    /** 剪切当前选区。 */
    Cut("shortcut_cut_key", R.string.shortcut_cut, ShortcutChord.FN),

    /** 粘贴剪贴板内容。 */
    Paste("shortcut_paste_key", R.string.shortcut_paste, ShortcutChord.FN),

    /** 全删：全选后整段删掉。 */
    ClearAll("shortcut_clear_all_key", R.string.shortcut_clear_all, ShortcutChord.FN),

    /** 撤销：向编辑器发 Ctrl+Z。 */
    Undo("shortcut_undo_key", R.string.shortcut_undo, ShortcutChord.FN),

    /** 光标左移一格。 */
    CursorLeft("shortcut_cursor_left_key", R.string.shortcut_cursor_left, ShortcutChord.FN),

    /** 光标右移一格。 */
    CursorRight("shortcut_cursor_right_key", R.string.shortcut_cursor_right, ShortcutChord.FN),

    /** 光标上移一行。 */
    CursorUp("shortcut_cursor_up_key", R.string.shortcut_cursor_up, ShortcutChord.FN),

    /** 光标下移一行。 */
    CursorDown("shortcut_cursor_down_key", R.string.shortcut_cursor_down, ShortcutChord.FN),

    // —— 选字类（chord=FN，Fn+U/J/H/K：U=上 J=下 H=左 K=右，与 Fn 光标簇 S/F/E/D 成对）——

    /** 选区向上扩一行（Fn+U）。 */
    SelectUp("shortcut_select_up_key", R.string.shortcut_select_up, ShortcutChord.FN),

    /** 选区向下扩一行（Fn+J）。 */
    SelectDown("shortcut_select_down_key", R.string.shortcut_select_down, ShortcutChord.FN),

    /** 选区向左扩一格（Fn+H）。 */
    SelectLeft("shortcut_select_left_key", R.string.shortcut_select_left, ShortcutChord.FN),

    /** 选区向右扩一格（Fn+K）。 */
    SelectRight("shortcut_select_right_key", R.string.shortcut_select_right, ShortcutChord.FN),
}
