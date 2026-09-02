/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.app.AlertDialog
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.PinyinUserDictEntry
import org.fcitx.fcitx5.android.core.enumeratePinyinUserDict
import org.fcitx.fcitx5.android.core.removePinyinUserDictWord
import org.fcitx.fcitx5.android.core.clearPinyinUserDict
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.MainViewModel

class PinyinUserDictFragment : Fragment(), OnItemChangedListener<PinyinUserDictEntry> {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var ui: BaseDynamicListUi<PinyinUserDictEntry>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ui = object : BaseDynamicListUi<PinyinUserDictEntry>(
            requireContext(),
            BaseDynamicListUi.Mode.Custom(),
            emptyList()
        ) {
            init {
                enableUndo = false
                addTouchCallback()
                shouldShowFab = false
                setViewModel(viewModel)
            }

            override fun showEntry(x: PinyinUserDictEntry): String = "${x.word}  ${x.pinyin}"

            override fun updateFAB() {
                // read-only enumeration + swipe-to-delete, no FAB
                fab.hide()
            }
        }
        ui.addOnItemChangedListener(this)
        viewModel.enableToolbarEditButton(ui.entries.isNotEmpty()) {
            ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
        }
        lifecycleScope.launch {
            val items = viewModel.fcitx.runOnReady { enumeratePinyinUserDict() }
            ui.addItems(items)
            if (items.isNotEmpty()) {
                viewModel.enableToolbarClearButton { showClearConfirm() }
            }
        }
        return ui.root
    }

    override fun onItemRemoved(idx: Int, item: PinyinUserDictEntry) {
        lifecycleScope.launch {
            viewModel.fcitx.runOnReady {
                removePinyinUserDictWord(item.pinyin, item.word)
            }
        }
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinUserDictEntry>>) {
        batchRemove(indexed)
    }

    private fun showClearConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear)
            .setMessage(R.string.confirm_clear_user_dict)
            .setPositiveButton(android.R.string.ok) { _, _ -> clearAll() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAll() {
        lifecycleScope.launch {
            viewModel.fcitx.runOnReady { clearPinyinUserDict() }
            ui.exitMultiSelect()
            ui.clearItems()
            viewModel.disableToolbarClearButton()
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.user_dict))
        viewModel.enableToolbarEditButton(ui.entries.isNotEmpty()) {
            ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
        }
        if (ui.entries.isNotEmpty()) {
            viewModel.enableToolbarClearButton { showClearConfirm() }
        }
    }

    override fun onStop() {
        ui.exitMultiSelect()
        viewModel.disableToolbarEditButton()
        viewModel.disableToolbarClearButton()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeItemChangedListener()
        super.onDestroy()
    }
}
