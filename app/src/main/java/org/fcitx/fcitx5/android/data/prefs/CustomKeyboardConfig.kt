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
    /**
     * 出厂默认：10 键，每键「一层四符」结构（参考普通键盘布局）：
     *  - 第一字符（键帽大字 / 单击默认）：最常用符号，顺序参照普通键盘标点区（`. , ? : ; ' " - / _`）；
     *  - 第二字符（右上小字 / 长按候选）：该键位数字 `1-9-0`（按位置）；
     *  - 第三字符（长按候选）：普通键盘数字键上方的符号 `! @ # $ % ^ & * ( )`（按位置）；
     *  - 第四字符（长按候选）：未覆盖在前三层的常用半角符号，按符号族成对排列
     *    `+ =` / `[ ]` / `{ }` / `< >` / `\ |`（按位置）。
     * 长按弹出 [主符号, 数字, 数字上方符号, 第四符号] 四选一；用户可自由改任意键。
     */
    val keys: List<CustomKeyConfig> = listOf(
        CustomKeyConfig(listOf(".", "1", "!", "+")),  // 句点 / 1 / ! / +
        CustomKeyConfig(listOf(",", "2", "@", "=")),  // 逗号 / 2 / @ / =
        CustomKeyConfig(listOf("?", "3", "#", "[")),  // 问号 / 3 / # / [
        CustomKeyConfig(listOf(":", "4", "$", "]")),  // 冒号 / 4 / $ / ]
        CustomKeyConfig(listOf(";", "5", "%", "{")),  // 分号 / 5 / % / {
        CustomKeyConfig(listOf("'", "6", "^", "}")),  // 单引号 / 6 / ^ / }
        CustomKeyConfig(listOf("\"", "7", "&", "<")),  // 双引号 / 7 / & / <
        CustomKeyConfig(listOf("-", "8", "*", ">")),  // 连字符 / 8 / * / >
        CustomKeyConfig(listOf("/", "9", "(", "\\")),  // 斜杠 / 9 / ( / \
        CustomKeyConfig(listOf("_", "0", ")", "|")),   // 下划线 / 0 / ) / |
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
