/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * 三个可循环的面板模块：符号 / 表情 / 自定义。顺序与开关由
 * [org.fcitx.fcitx5.android.data.prefs.AppPrefs.PanelCycle] 决定。
 * 顶栏常驻按钮、屏幕 `!?#` 键、物理 SYM 键共用同一套有序循环逻辑。
 */
enum class PanelModule { SYMBOL, EMOJI, CUSTOM }
