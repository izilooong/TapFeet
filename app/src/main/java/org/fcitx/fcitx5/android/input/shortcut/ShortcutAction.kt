/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

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
 * （见 [org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles.shortcutValuesFor]）：Titan 系写
 * `Fn+字母`（伪修饰键和弦，见 [org.fcitx.fcitx5.android.data.prefs.HardwareChord]）。
 *
 * ⚠️ **BlackBerry（Q25）整体不提供这一套** —— 那台机器上下两个 Alt 一个都不能占（左 Alt + 字母是
 * 系统原生的键帽符号输入，右 Alt 就是 SYM 键本身），而真修饰键的 meta 又分不出左右 Alt，等于没有
 * 空闲修饰键可以承载和弦。判据见 [org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles.actionShortcutsAvailable]：
 * 设置页入口的可见性与运行期匹配**共用它**，所以那边既看不到入口，也不会有幽灵绑定在抢键。
 * 设置页的「恢复推荐键位」随时能重播（在不支持该套配置的预设下等价于清空）。
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
}
