/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareChord
import org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles
import org.fcitx.fcitx5.android.data.prefs.HardwareSpecialKeys
import org.fcitx.fcitx5.android.input.shortcut.ShortcutAction
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.KeyCapturePreference
import org.fcitx.fcitx5.android.ui.main.settings.KeyCaptureUi
import org.fcitx.fcitx5.android.utils.normalizeKeyString

/**
 * 「快捷键」配置页：为常用动作绑定物理键。
 *
 * 逐行渲染 [ShortcutAction]（单一事实来源），每行直接复用 [KeyCapturePreference] ——
 * 捕获 / 修改 / 重置三件套它自带，本页不重写任何捕获或渲染逻辑，只额外接一个冲突提示。
 *
 * 与「物理键绑定」页的分工：那边管**输入行为**（候选字 / 翻页 / 符号窗口 / 切输入法），由
 * keyProfile 预设统一播种；这边管**动作开关**，纯用户自定义、不受预设影响。冲突检测会把两边的
 * 键位一起算进来 —— 它们抢的是同一个物理键盘。
 */
class ShortcutKeysSettingsFragment : PaddingPreferenceFragment() {

    private lateinit var shortcuts: AppPrefs.Shortcuts
    private val keyPrefs = mutableListOf<KeyCapturePreference>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        shortcuts = AppPrefs.getInstance().shortcuts

        // 说明行。两点必须说清：① 只在物理键按下时生效（软键盘点按不经过 onKeyDown）；
        // ② 留空 = 不绑定 —— 本页默认全空，用户看到一片「无」得知道那是有意为之，而不是坏了。
        screen.addPreference(Preference(context).apply {
            key = "shortcut_intro"
            title = getString(R.string.shortcut_intro)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            isSelectable = false
        })

        ShortcutAction.entries.forEach { action ->
            val pref = shortcuts.key(action)
            val capture = KeyCapturePreference(context).apply {
                key = pref.key
                title = getString(action.titleRes)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                // 传**常量默认值**（空串 = 不绑定），而不是当前值：传当前值会让对话框里的「重置」
                // 按钮把当前值再写一遍，等于空操作（既有的几处调用点正是这个毛病）。
                setDefaultValue(pref.defaultValue)
                summaryProvider = KeyCapturePreference.KeySummaryProvider
                conflictHint = { candidate -> conflictMessage(candidate, pref.key) }
            }
            screen.addPreference(capture)
            keyPrefs.add(capture)
        }

        // 恢复推荐键位：按当前**键盘预设**播一套推荐动作键（Titan 系 = `Fn+字母`，伪修饰键和弦）。
        // 预设若整体不提供这套配置（BlackBerry，见 HardwareKeyProfiles.actionShortcutsAvailable），
        // 同一个 shortcutValuesFor 会返回**全空串** ⇒ 这里等价于「清空」，不必另写清空逻辑。
        // 首次安装与切换键盘预设时也走同一份（HardwareKeyProfiles.applyShortcutPreset）。
        // 注：黑莓预设下本页是隐藏的（MainFragment 按同一判据显隐），这个按钮到不了。
        screen.addPreference(Preference(context).apply {
            key = "shortcut_apply_preset"
            title = getString(R.string.shortcut_apply_preset)
            val profile = AppPrefs.getInstance().hardwareKeyboard.keyProfile.getValue()
            summary = getString(
                R.string.shortcut_apply_preset_summary,
                getString(HardwareKeyProfiles.labelResFor(profile))
            )
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                HardwareKeyProfiles.applyShortcutPreset(
                    AppPrefs.getInstance().hardwareKeyboard.keyProfile.getValue(),
                    AppPrefs.getInstance()
                )
                keyPrefs.forEach { it.refresh() }
                true
            }
        })

        screen.addPreference(Preference(context).apply {
            key = "shortcut_reset_all"
            title = getString(R.string.shortcut_reset_all)
            summary = getString(R.string.shortcut_reset_all_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                ShortcutAction.entries.forEach { action -> shortcuts.key(action).setValue("") }
                // 外部直接写 SharedPreferences 不会刷新 androidx Preference 的摘要，必须手动 refresh
                // （applyProfile / applyQuickPick 也是这么做的）。
                keyPrefs.forEach { it.refresh() }
                true
            }
        })

        preferenceScreen = screen
    }

    /** 一条已存在的键位绑定，用于冲突检测。 */
    private data class Binding(
        val prefKey: String,
        val title: CharSequence,
        val value: String,
    )

    /**
     * 全量绑定快照。刻意跨分类：除了本页的动作绑定，还要算上「物理键绑定」页那批候选字 / 翻页 /
     * 符号 / 输入法切换 / Alt 锁定键 —— 它们同样是物理键绑定，撞上了照样互相抢。
     *
     * 每次弹窗前现算（十几次 SharedPreferences 读，可忽略），不缓存：缓存了就会在用户刚改完
     * 另一行之后给出过期的冲突提示。
     */
    private fun bindings(): List<Binding> {
        val ctx = requireContext()
        val hw = AppPrefs.getInstance().hardwareKeyboard
        val hwEntries = listOf(
            hw.candidate1Key to R.string.candidate_key_1,
            hw.candidate2Key to R.string.candidate_key_2,
            hw.candidate3Key to R.string.candidate_key_3,
            hw.candidate4Key to R.string.candidate_key_4,
            hw.candidate5Key to R.string.candidate_key_5,
            hw.pageNextKey to R.string.candidate_page_next,
            hw.pagePrevKey to R.string.candidate_page_prev,
            hw.symbolPickerKey to R.string.hw_symbol_picker,
            hw.toggleImeKey to R.string.hw_toggle_ime,
            hw.pickerKey to R.string.hw_show_picker,
            hw.altLatchKey to R.string.hw_alt_latch_key,
        ).map { (pref, titleRes) -> Binding(pref.key, ctx.getString(titleRes), pref.getValue()) }

        val actionEntries = ShortcutAction.entries.map { action ->
            val pref = shortcuts.key(action)
            Binding(pref.key, ctx.getString(action.titleRes), pref.getValue())
        }
        return hwEntries + actionEntries
    }

    /**
     * 这个键撞了谁？返回一句说明，或 null 表示没冲突。
     *
     * 比较的是**归一化后的绑定**而不是原始字符串：`Key.parse` 接受同一按键的多种写法，直接比
     * 字符串会漏判。伪按键（Sym / NavBack）没有 KeySym，按名字比。
     * 和弦（"Fn+e" / "Sym+e"）先比前缀再比内层 —— 前缀不同就是不同手势（一个要按住 Fn，
     * 一个要按住右 Alt），不算冲突。
     */
    private fun conflictMessage(candidate: String, selfKey: String): CharSequence? {
        if (candidate.isEmpty()) return null
        val owner = bindings().firstOrNull { it.prefKey != selfKey && sameBinding(it.value, candidate) }
            ?: return null
        return requireContext().getString(
            R.string.shortcut_conflict_warning,
            KeyCaptureUi.formatKey(requireContext(), candidate),
            owner.title,
        )
    }

    private fun sameBinding(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val (chordA, innerA) = HardwareChord.split(a)
        val (chordB, innerB) = HardwareChord.split(b)
        if (chordA != chordB) return false
        if (innerA.isEmpty() || innerB.isEmpty()) return false
        val specialA = HardwareSpecialKeys.entryForName(innerA)
        val specialB = HardwareSpecialKeys.entryForName(innerB)
        if (specialA != null || specialB != null) return specialA?.name == specialB?.name
        return Key.parse(normalizeKeyString(innerA)).portableString ==
                Key.parse(normalizeKeyString(innerB)).portableString
    }
}
