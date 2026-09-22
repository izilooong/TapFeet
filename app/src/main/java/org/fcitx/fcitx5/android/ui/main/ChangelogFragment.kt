/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import timber.log.Timber

class ChangelogFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_changelog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val text = runCatching {
            resources.openRawResource(R.raw.changelog).bufferedReader().use { it.readText() }
        }.getOrDefault("").also {
            if (it.isEmpty()) Timber.w("changelog resource is empty or missing")
        }
        recycler.adapter = ChangelogAdapter(parseChangelog(text))
        // 底部留出导航栏安全区，避免最后一张卡片被遮挡（baseBottom 固定，避免 inset 多次累加）
        val baseBottom = recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(recycler) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + nav.bottom)
            insets
        }
    }
}

/**
 * 把 [docs/更新内容.txt] 格式的纯文本解析成版本列表：
 * - `#` 开头 = 版本标题（剥掉「更新内容」前缀，只留版本号）
 * - `-` / `·` 开头 = 条目
 * - 空行 / 顶部仓库链接 = 跳过
 * 返回最新版本置顶。
 */
private fun parseChangelog(text: String): List<ChangelogVersion> {
    val versions = mutableListOf<ChangelogVersion>()
    var title = ""
    var items = mutableListOf<String>()
    fun flush() {
        if (title.isNotEmpty() && items.isNotEmpty()) {
            versions += ChangelogVersion(title, items.toList())
        }
        items = mutableListOf()
    }
    for (raw in text.lineSequence()) {
        val line = raw.trimEnd()
        when {
            line.startsWith("#") -> {
                flush()
                title = line.removePrefix("#").trim()
                    .removePrefix("更新内容").trim()
                    .ifEmpty { line.removePrefix("#").trim() }
            }
            line.startsWith("-") || line.startsWith("·") ->
                items += line.removePrefix("-").removePrefix("·").trim()
            line.isBlank() || line.startsWith("http") -> Unit
            else -> if (title.isNotEmpty()) items += line.trim()
        }
    }
    flush()
    return versions.reversed()
}
