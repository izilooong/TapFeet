/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 单个自定义键的配置：字符列表。
 * 第一个非空字符 = 默认字符（单击输入，也作键帽显示），其余为长按弹选的选项。
 */
@Serializable
data class CustomKeyConfig(val chars: List<String> = emptyList())

object CustomKeyboardDefaults {
    /** 出厂默认：10 组常用符号（成对符号分左右，按首选→次选排列），用户可自由改 */
    val keys: List<CustomKeyConfig> = listOf(
        CustomKeyConfig(listOf("(", "[", "{", "<", "（", "【", "｛", "＜")), // 左括号（含左尖括号）
        CustomKeyConfig(listOf(")", "]", "}", ">", "）", "】", "｝", "＞")), // 右括号（含右尖括号）
        CustomKeyConfig(listOf("\"", "'", "“", "‘")),                     // 左引号（半角双/单 → 全角双/单）
        CustomKeyConfig(listOf("\"", "'", "”", "’")),                     // 右引号（半角双/单 → 全角右双/单）
        CustomKeyConfig(listOf(".", "．", "。")),                          // 句号 / 点
        CustomKeyConfig(listOf(",", "，", "、")),                          // 逗号 / 顿号
        CustomKeyConfig(listOf("?", "!", "？", "！")),                     // 问号 / 感叹号（先半角后全角）
        CustomKeyConfig(listOf(";", ":", "；", "：")),                     // 分号 / 冒号
        CustomKeyConfig(listOf("…", "—", "～", "·")),                      // 特殊符号（省略号、破折号、波浪号、间隔号）
        CustomKeyConfig(listOf("%", "&", "$", "@", "#", "*", "/", "\\")), // 网络/编程常用符号（全部半角）
    )
}

object CustomKeyboardCodec : ManagedPreference.StringLikeCodec<List<CustomKeyConfig>> {
    override fun encode(x: List<CustomKeyConfig>): String = Json.encodeToString(x)

    override fun decode(raw: String): List<CustomKeyConfig>? =
        try {
            Json.decodeFromString(raw)
        } catch (_: Exception) {
            null // 解码失败 → PStringLike 回落默认值
        }
}
