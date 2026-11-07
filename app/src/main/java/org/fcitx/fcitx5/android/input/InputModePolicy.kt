/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode

/** 触发键盘模式重算的来源。不同来源对「系统怎么说」「当前模式」的容错不同。 */
enum class InputModeTrigger {
    StartInputView,
    KeyDown,
    ViewClicked,
    ToolTypeUpdate,
}

/**
 * 虚拟 / 物理键盘模式的**唯一判定**。
 *
 * 四种来源原本在 [InputDeviceManager] 里各写一遍 `when (candidatesViewMode)`，连
 * SystemDefault 的容错分支都有两种变体；改一处漏一处就是那种最难查的静默失效。这里收成一份，
 * [InputDeviceManager] 只负责把 Framework 事件翻译成入参，不再保存第二份判定规则。
 *
 * [systemWantsVirtual] 惰性求值：只有 SystemDefault 会去问系统，其余模式一次都不碰它
 * （与旧实现的短路行为一致）。
 */
object InputModePolicy {
    fun resolve(
        mode: FloatingCandidatesMode,
        trigger: InputModeTrigger,
        currentVirtual: Boolean,
        systemWantsVirtual: () -> Boolean,
        isTouchTool: Boolean = false,
    ): Boolean = when (mode) {
        FloatingCandidatesMode.Disabled -> true
        FloatingCandidatesMode.SystemDefault -> when (trigger) {
            InputModeTrigger.StartInputView, InputModeTrigger.KeyDown -> systemWantsVirtual()
            // 浮动编辑窗 / tool-type 更新会短暂误报 false，别立刻收起已经显示的虚拟键盘
            InputModeTrigger.ViewClicked, InputModeTrigger.ToolTypeUpdate ->
                systemWantsVirtual() || currentVirtual
        }
        FloatingCandidatesMode.InputDevice -> when (trigger) {
            InputModeTrigger.StartInputView -> currentVirtual
            InputModeTrigger.KeyDown -> false
            InputModeTrigger.ViewClicked -> true
            InputModeTrigger.ToolTypeUpdate -> if (isTouchTool) true else currentVirtual
        }
    }
}
