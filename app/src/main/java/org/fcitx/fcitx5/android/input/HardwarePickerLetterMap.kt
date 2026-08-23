/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input

import android.view.KeyEvent

/**
 * 把物理键盘 26 个字母键（Q..M，按物理行分三行）映射到符号窗口网格的 (row, col) 位置，
 * 供符号窗口打开时用物理键盘直接选符号（BlackBerry SYM 面板）。
 *
 * - row0 (QWERTY): Q..P → col 0..9
 * - row1 (ASDF):   A..L → col 0..8
 * - row2 (ZXCV):   Z..M → col 0..6
 *
 * 三个页签（Symbol / Emoji / Emoticon）与最近使用页统一使用键盘形网格，
 * 由 PickerWindow.selectByLetter 换算为线性序 idx（R0:0-9 / R1:10-18 / R2:19-25）。
 *
 * 仅负责 (row, col) 映射；具体网格 idx 计算（symbol 键盘形主页的线性序、Emoji/Emoticon
 * 方阵序、列超界吞键）由 PickerWindow.selectByLetter 统一处理。
 */
object HardwarePickerLetterMap {

    data class Position(val row: Int, val col: Int)

    private val map: Map<Int, Position> = mapOf(
        // QWERTY 行
        KeyEvent.KEYCODE_Q to Position(0, 0),
        KeyEvent.KEYCODE_W to Position(0, 1),
        KeyEvent.KEYCODE_E to Position(0, 2),
        KeyEvent.KEYCODE_R to Position(0, 3),
        KeyEvent.KEYCODE_T to Position(0, 4),
        KeyEvent.KEYCODE_Y to Position(0, 5),
        KeyEvent.KEYCODE_U to Position(0, 6),
        KeyEvent.KEYCODE_I to Position(0, 7),
        KeyEvent.KEYCODE_O to Position(0, 8),
        KeyEvent.KEYCODE_P to Position(0, 9),
        // ASDF 行
        KeyEvent.KEYCODE_A to Position(1, 0),
        KeyEvent.KEYCODE_S to Position(1, 1),
        KeyEvent.KEYCODE_D to Position(1, 2),
        KeyEvent.KEYCODE_F to Position(1, 3),
        KeyEvent.KEYCODE_G to Position(1, 4),
        KeyEvent.KEYCODE_H to Position(1, 5),
        KeyEvent.KEYCODE_J to Position(1, 6),
        KeyEvent.KEYCODE_K to Position(1, 7),
        KeyEvent.KEYCODE_L to Position(1, 8),
        // ZXCV 行
        KeyEvent.KEYCODE_Z to Position(2, 0),
        KeyEvent.KEYCODE_X to Position(2, 1),
        KeyEvent.KEYCODE_C to Position(2, 2),
        KeyEvent.KEYCODE_V to Position(2, 3),
        KeyEvent.KEYCODE_B to Position(2, 4),
        KeyEvent.KEYCODE_N to Position(2, 5),
        KeyEvent.KEYCODE_M to Position(2, 6)
    )

    fun positionOfKeyCode(keyCode: Int): Position? = map[keyCode]
}
