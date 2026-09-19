/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import kotlinx.serialization.json.Json

/**
 * 「符号 / 表情 / 自定义」三个面板的循环顺序（按用户设置的先后排列）。
 * 自定义键盘的开关复用 [AppPrefs.CustomKeyboard.enabled]，这里只存顺序与另外两个开关。
 */
object PanelCycleDefaults {
    /** 出厂默认：符号 → 表情 → 自定义 */
    val order: List<String> = listOf("symbol", "emoji", "custom")
}

object PanelCycleCodec : ManagedPreference.StringLikeCodec<List<String>> {
    override fun encode(x: List<String>): String = Json.encodeToString(x)

    override fun decode(raw: String): List<String>? =
        try {
            Json.decodeFromString(raw)
        } catch (_: Exception) {
            null // 解码失败 → PStringLike 回落默认值
        }
}
