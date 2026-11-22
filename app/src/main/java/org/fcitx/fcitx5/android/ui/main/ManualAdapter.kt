/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ItemManualChapterBinding
import org.fcitx.fcitx5.android.ui.common.CardGroupDecoration
import splitties.resources.styledColor
import kotlin.math.roundToInt

/** 章节卡片列表：点标题折叠 / 展开正文。展开状态按章节标题保存，滚动不丢失。 */
class ManualAdapter(private val chapters: List<ManualChapter>) :
    RecyclerView.Adapter<ManualAdapter.ViewHolder>() {

    private val expanded = mutableSetOf<String>()

    class ViewHolder(val binding: ItemManualChapterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemManualChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.card.background =
            CardGroupDecoration.shapeFor(parent.context, isFirst = true, isLast = true)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = chapters.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chapter = chapters[position]
        val binding = holder.binding
        binding.title.text = chapter.title
        val isExpanded = chapter.title in expanded
        binding.content.isVisible = isExpanded
        binding.arrow.text = if (isExpanded) "▼" else "▶"
        binding.arrow.contentDescription =
            binding.root.context.getString(if (isExpanded) R.string.manual_collapse else R.string.manual_expand)

        binding.header.setOnClickListener {
            if (chapter.title in expanded) {
                expanded -= chapter.title
                binding.content.isVisible = false
                binding.arrow.text = "▶"
                binding.arrow.contentDescription =
                    it.context.getString(R.string.manual_expand)
            } else {
                expanded += chapter.title
                bindContent(binding.content, chapter.blocks)
                binding.content.isVisible = true
                binding.arrow.text = "▼"
                binding.arrow.contentDescription =
                    it.context.getString(R.string.manual_collapse)
            }
        }
        if (isExpanded && binding.content.childCount == 0) {
            bindContent(binding.content, chapter.blocks)
        }
    }

    /** 把解析后的块渲染进正文容器（只在展开时构建，折叠态零开销）。 */
    private fun bindContent(container: LinearLayout, blocks: List<ManualBlock>) {
        container.removeAllViews()
        val ctx = container.context
        blocks.forEach { block ->
            when (block) {
                is ManualBlock.SubHeading -> container.addView(
                    TextView(ctx).apply {
                        text = inline(block.text)
                        textSize = 15f
                        setTextColor(ctx.styledColor(android.R.attr.colorAccent))
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = matchWrap(top = 14.dp(ctx))
                    }
                )

                is ManualBlock.Paragraph -> container.addView(
                    TextView(ctx).apply {
                        text = inline(block.text)
                        textSize = 14f
                        setTextColor(ctx.styledColor(android.R.attr.textColorPrimary))
                        layoutParams = matchWrap(top = 6.dp(ctx))
                    }
                )

                is ManualBlock.Bullet -> container.addView(
                    TextView(ctx).apply {
                        text = inline("• ${block.text}")
                        textSize = 14f
                        setTextColor(ctx.styledColor(android.R.attr.textColorPrimary))
                        layoutParams = matchWrap(top = 4.dp(ctx), left = 6.dp(ctx))
                    }
                )

                is ManualBlock.Quote -> container.addView(
                    TextView(ctx).apply {
                        text = inline(block.text)
                        textSize = 13f
                        setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
                        layoutParams = matchWrap(top = 6.dp(ctx), left = 10.dp(ctx))
                    }
                )

                is ManualBlock.Table -> container.addView(buildTable(ctx, block))
            }
        }
    }

    private fun buildTable(ctx: android.content.Context, table: ManualBlock.Table): TableLayout {
        val dp = { v: Int -> v.dp(ctx) }
        return TableLayout(ctx).apply {
            isStretchAllColumns = true
            layoutParams = matchWrap(top = 8.dp(ctx))
            val allRows = listOf(table.header) + table.rows
            allRows.forEachIndexed { rowIndex, cells ->
                addView(TableRow(ctx).apply {
                    layoutParams = TableLayout.LayoutParams(
                        TableLayout.LayoutParams.MATCH_PARENT,
                        TableLayout.LayoutParams.WRAP_CONTENT
                    )
                    cells.forEach { cell ->
                        addView(TextView(ctx).apply {
                            text = inline(cell)
                            textSize = 12f
                            setTextColor(
                                if (rowIndex == 0) ctx.styledColor(android.R.attr.colorAccent)
                                else ctx.styledColor(android.R.attr.textColorPrimary)
                            )
                            if (rowIndex == 0) typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                        })
                    }
                })
            }
        }
    }

    /** 行内 **粗体** 与 `代码` 标记转 Spannable，其余原样。 */
    private fun inline(text: String): Spanned {
        val ssb = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        ssb.append(
                            text.substring(i + 2, end),
                            StyleSpan(Typeface.BOLD),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 2
                        continue
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        ssb.append(
                            text.substring(i + 1, end),
                            StyleSpan(Typeface.ITALIC),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 1
                        continue
                    }
                }
            }
            ssb.append(text[i])
            i++
        }
        return ssb
    }

    private fun matchWrap(top: Int = 0, left: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(left, top, 0, 0) }

    private fun Int.dp(ctx: android.content.Context): Int =
        (this * ctx.resources.displayMetrics.density).roundToInt()
}
