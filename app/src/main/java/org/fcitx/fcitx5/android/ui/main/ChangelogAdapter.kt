/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.databinding.ItemChangelogBinding
import org.fcitx.fcitx5.android.ui.common.CardGroupDecoration

data class ChangelogVersion(val title: String, val items: List<String>)

class ChangelogAdapter(private val versions: List<ChangelogVersion>) :
    RecyclerView.Adapter<ChangelogAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemChangelogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChangelogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // 卡片背景复用设置页同款样式（card_bg 填充 + 圆角）；不用 MaterialCardView，规避主题强校验
        binding.root.background =
            CardGroupDecoration.shapeFor(parent.context, isFirst = true, isLast = true)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = versions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val version = versions[position]
        holder.binding.title.text = version.title
        holder.binding.items.text = version.items.joinToString("\n") { "• $it" }
    }
}
