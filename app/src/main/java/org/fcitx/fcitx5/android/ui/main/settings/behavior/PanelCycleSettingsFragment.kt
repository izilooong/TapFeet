/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.MainViewModel

/**
 * 「面板循环」配置页：三个面板模块（符号 / 表情 / 自定义）的独立开关 + 拖拽排序。
 * 排序落到 [AppPrefs.PanelCycle.panelOrder]，开关各自落到对应偏好
 * （符号→[AppPrefs.PanelCycle.symbolPanelEnabled]、表情→[AppPrefs.PanelCycle.emojiPanelEnabled]、
 * 自定义→[AppPrefs.CustomKeyboard.enabled]）。循环起点与顺序由顶栏按钮 / !?# 键 / SYM 键统一读取。
 */
class PanelCycleSettingsFragment :
    Fragment(),
    OnItemChangedListener<PanelCycleSettingsFragment.PanelModuleItem> {

    private val prefs = AppPrefs.getInstance()
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var ui: BaseDynamicListUi<PanelModuleItem>

    /** 三个面板模块的标识；顺序由 [AppPrefs.PanelCycle.panelOrder] 决定。 */
    data class PanelModuleItem(val id: String) {
        val labelRes: Int
            get() = when (id) {
                "symbol" -> R.string.panel_module_symbol
                "emoji" -> R.string.panel_module_emoji
                "custom" -> R.string.panel_module_custom
                else -> R.string.panel_cycle_settings
            }
    }

    private fun moduleEnabled(item: PanelModuleItem): Boolean = when (item.id) {
        "symbol" -> prefs.panelCycle.symbolPanelEnabled.getValue()
        "emoji" -> prefs.panelCycle.emojiPanelEnabled.getValue()
        "custom" -> prefs.customKeyboard.enabled.getValue()
        else -> false
    }

    private fun setModuleEnabled(item: PanelModuleItem, enabled: Boolean) {
        when (item.id) {
            "symbol" -> prefs.panelCycle.symbolPanelEnabled.setValue(enabled)
            "emoji" -> prefs.panelCycle.emojiPanelEnabled.setValue(enabled)
            "custom" -> prefs.customKeyboard.enabled.setValue(enabled)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val initialEntries = prefs.panelCycle.panelOrder.getValue().map { PanelModuleItem(it) }
        ui = object : BaseDynamicListUi<PanelModuleItem>(
            requireContext(),
            BaseDynamicListUi.Mode.Immutable(),
            initialEntries,
            enableOrder = true,
            initCheckBox = { entry ->
                isChecked = moduleEnabled(entry)
                setOnCheckedChangeListener { _, checked ->
                    setModuleEnabled(entry, checked)
                }
            }
        ) {
            override fun showEntry(x: PanelModuleItem): String = getString(x.labelRes)
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        return ui.root
    }

    override fun onItemSwapped(fromIdx: Int, toIdx: Int, item: PanelModuleItem) {
        prefs.panelCycle.panelOrder.setValue(ui.entries.map { it.id })
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.panel_cycle_settings))
    }
}
