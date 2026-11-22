/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.ui.main

/**
 * 使用手册（docs/USER_MANUAL.md）的轻量 Markdown 解析：只解析手册实际用到的子集，
 * 不引第三方库。章节 = `##` 一级标题，正文按行切成段落 / 小节 / 表格 / 列表 / 引用。
 */
data class ManualChapter(val title: String, val blocks: List<ManualBlock>)

sealed class ManualBlock {
    data class SubHeading(val text: String) : ManualBlock()          // ### / ####
    data class Paragraph(val text: String) : ManualBlock()           // 普通段落（行内可含 **粗体** / `代码`）
    data class Bullet(val text: String) : ManualBlock()              // - / 数字. 列表项
    data class Quote(val text: String) : ManualBlock()               // > 引用
    data class Table(val header: List<String>, val rows: List<List<String>>) : ManualBlock()
}

/**
 * 解析手册文本为章节列表。`#` 文档标题与前言跳过；`##` 开新章节。
 * 表格需连续 `|` 行，第二行为 `|---|` 分隔行时跳过。空行收束当前段落。
 */
internal fun parseManual(text: String): List<ManualChapter> {
    val chapters = mutableListOf<ManualChapter>()
    var title: String? = null
    val blocks = mutableListOf<ManualBlock>()
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) {
            blocks += ManualBlock.Paragraph(para.toString().trim())
            para.clear()
        }
    }
    fun flushChapter() {
        flushPara()
        if (title != null && blocks.isNotEmpty()) {
            chapters += ManualChapter(title!!, blocks.toList())
        }
        blocks.clear()
    }

    val lines = text.lineSequence().toList()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val t = line.trim()
        when {
            t.startsWith("## ") -> {
                flushChapter()
                title = t.removePrefix("## ").trim()
            }
            // 文档大标题 / 前言 / 水平线跳过
            t.startsWith("# ") || t == "---" -> flushPara()
            title == null -> Unit // ## 之前的引言不算任何章节
            t.startsWith("### ") || t.startsWith("#### ") -> {
                flushPara()
                blocks += ManualBlock.SubHeading(t.trimStart('#').trim())
            }
            t.startsWith("|") -> {
                flushPara()
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    tableLines += lines[i].trim()
                    i++
                }
                // while 已把 i 停在首个非表格行；continue 会跳过末尾的 i++，
                // 下一轮正好处理这一行。切勿再 i--，否则回退到表格末行导致本表被无限重解析。
                val cells = tableLines.map { row ->
                    row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                }.filterNot { row -> row.all { it.matches(Regex(":?-+:?")) } } // 去掉 |---| 分隔行
                if (cells.isNotEmpty()) {
                    blocks += ManualBlock.Table(cells.first(), cells.drop(1))
                }
                continue
            }
            t.startsWith("> ") -> {
                flushPara()
                blocks += ManualBlock.Quote(t.removePrefix("> ").trim())
            }
            t.startsWith("- ") || t.startsWith("· ") -> {
                flushPara()
                blocks += ManualBlock.Bullet(t.drop(2).trim())
            }
            t.matches(Regex("^\\d+\\.\\s+.*")) -> {
                flushPara()
                blocks += ManualBlock.Bullet(t.replaceFirst(Regex("^\\d+\\.\\s+"), ""))
            }
            t.isEmpty() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
        i++
    }
    flushChapter()
    return chapters
}
