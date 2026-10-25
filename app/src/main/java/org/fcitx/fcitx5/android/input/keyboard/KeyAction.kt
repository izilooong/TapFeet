/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.input.picker.PickerWindow

sealed class KeyAction {

    data class FcitxKeyAction(
        val act: String,
        val code: Int = ScancodeMapping.charToScancode(act[0]),
        val states: KeyStates = KeyStates.Virtual
    ) : KeyAction()

    data class SymAction(val sym: KeySym, val states: KeyStates = KeyStates.Virtual) : KeyAction()

    data class CommitAction(val text: String) : KeyAction()

    data class CapsAction(val lock: Boolean) : KeyAction()

    data object QuickPhraseAction : KeyAction()

    data object UnicodeAction : KeyAction()

    data object LangSwitchAction : KeyAction()

    data object ShowInputMethodPickerAction : KeyAction()

    data class LayoutSwitchAction(val act: String = "") : KeyAction()

    data class MoveSelectionAction(val start: Int = 0, val end: Int = 0) : KeyAction()

    data class DeleteSelectionAction(val totalCnt: Int = 0) : KeyAction()

    data class PickerSwitchAction(val key: PickerWindow.Key? = null) : KeyAction()

    /**
     * 符号窗口按钮的统一触发动作：按「启用且排序后」的面板列表循环切换
     * （关闭 → 排序1 → 排序2 → … → 关闭）。屏幕 `!?#` 键、顶栏常驻按钮、物理 SYM 键共用。
     */
    data object PanelCycleAction : KeyAction()

    data object SpaceLongPressAction : KeyAction()

    /**
     * 常驻行左端「虚拟 Shift」键的单击：下一键大写（caps once 语义，键盘层私有状态）；
     * Lock 态下单击 = 解锁。处理在 [CustomKeyboard.onVirtualShiftTap]（键盘层消化，不外发）。
     */
    data object CustomShiftAction : KeyAction()

    /**
     * 常驻行左端「虚拟 Shift」键的双击：锁定/解锁大写（caps latch，与物理键盘长按 Shift 同一套
     * hardwareKeyDispatch.capsLatched 机制，KawaiiBar 大写指示按钮联动）。
     */
    data object CustomShiftLockAction : KeyAction()

    /** 常驻行右端「隐藏状态栏」键：翻转 [org.fcitx.fcitx5.android.data.prefs.AppPrefs.CandidateBar.hideStatusBar]。 */
    data object HideStatusBarAction : KeyAction()
}
