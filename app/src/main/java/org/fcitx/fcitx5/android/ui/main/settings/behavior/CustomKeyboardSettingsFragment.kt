/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.CustomKeyConfig
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference

/**
 * 「自定义一行键盘」配置页：固定 10 个键，每个键一个条目，
 * 点击弹出多行编辑框，每行一个字符/字符串，第一行为单击输入的默认字符。
 *
 * 保存后键盘实例由 [org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow.switchLayout]
 * 在下次切换到自定义键盘时重建，自动拿到新配置。
 */
class CustomKeyboardSettingsFragment : PaddingPreferenceFragment() {

    private val customKeyboard = AppPrefs.getInstance().customKeyboard
    private val keyPrefs = mutableListOf<Preference>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            // 总开关：与 AppPrefs.CustomKeyboard.enabled 同 key，切换即时生效（状态栏⑩/符号键盘⑩/Sym 循环响应）
            val enableSwitch = MySwitchPreference(context).apply {
                key = "custom_keyboard_enabled"
                title = getString(R.string.custom_keyboard_enabled)
                summary = getString(R.string.custom_keyboard_enabled_summary)
                setDefaultValue(true)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            }
            addPreference(enableSwitch)
            customKeyboard.keys.getValue().forEachIndexed { index, keyConfig ->
                val pref = Preference(context).apply {
                    title = getString(R.string.custom_keyboard_key_title, index + 1)
                    summary = summarize(keyConfig)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showEditKeyDialog(index, keyConfig)
                        true
                    }
                }
                addPreference(pref)
                keyPrefs.add(pref)
            }
            // 开关关闭时禁用下方按键条目
            keyPrefs.forEach { it.isEnabled = customKeyboard.enabled.getValue() }
            enableSwitch.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                keyPrefs.forEach { it.isEnabled = enabled }
                true
            }
        }
    }

    private fun summarize(config: CustomKeyConfig): String =
        config.chars.joinToString("  ") { if (it.isBlank()) "␣" else it }
            .ifEmpty { getString(R.string.custom_keyboard_empty) }

    private fun showEditKeyDialog(index: Int, config: CustomKeyConfig) {
        val input = EditText(requireContext()).apply {
            setText(config.chars.joinToString("\n"))
            gravity = Gravity.START or Gravity.TOP
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.custom_keyboard_key_title, index + 1))
            .setMessage(R.string.custom_keyboard_edit_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newChars = input.text.toString().lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                val all = customKeyboard.keys.getValue().toMutableList()
                all[index] = CustomKeyConfig(newChars)
                customKeyboard.keys.setValue(all)
                keyPrefs[index].summary = summarize(CustomKeyConfig(newChars))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
