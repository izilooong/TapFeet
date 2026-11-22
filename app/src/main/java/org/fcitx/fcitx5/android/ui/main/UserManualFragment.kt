/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
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

/** 使用手册页：读取随包内置的 docs/USER_MANUAL.md，按章节折叠展示。 */
class UserManualFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_user_manual, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val text = runCatching {
            resources.openRawResource(R.raw.user_manual).bufferedReader().use { it.readText() }
        }.getOrDefault("").also {
            if (it.isEmpty()) Timber.w("user manual resource is empty or missing")
        }
        recycler.adapter = ManualAdapter(parseManual(text))
        val baseBottom = recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(recycler) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + nav.bottom)
            insets
        }
    }
}
