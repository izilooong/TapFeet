/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareChord
import org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles
import org.fcitx.fcitx5.android.data.prefs.HardwareSpecialKeys
import org.fcitx.fcitx5.android.input.shortcut.ShortcutAction
import org.fcitx.fcitx5.android.input.shortcut.ShortcutChord
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.createSettingsTabBar
import org.fcitx.fcitx5.android.ui.main.settings.KeyCapturePreference
import org.fcitx.fcitx5.android.ui.main.settings.KeyCaptureUi
import org.fcitx.fcitx5.android.utils.normalizeKeyString

/**
 * 「快捷键」配置页：为常用动作绑定物理键。
 *
 * 两个**固定 Tab**（复用 [HardwareKeyboardSettingsFragment] 的 TabLayout 模式）：
 *  - **编辑**：编辑类（含选字四向，[ShortcutChord.FN]，Titan 系 `Fn+字母` / 黑莓 `Alt_R+字母`）；
 *  - **开关**：开关类（[ShortcutChord.SYM]，Titan 系 `Sym+字母` / 黑莓 `Alt_R+字母`）。
 *
 * 分组只按 [ShortcutAction.chord] 这一份标记走，本页不写第二份分类判断。每行直接复用
 * [KeyCapturePreference] —— 捕获 / 修改 / 重置三件套它自带，本页不重写任何捕获或渲染逻辑，
 * 只额外接一个冲突提示。「恢复推荐键位 / 全部解绑」作用于整页配置，两个 Tab 底部各放一份入口。
 *
 * 与「物理键绑定」页的分工：那边管**输入行为**（候选字 / 翻页 / 符号窗口 / 切输入法），由
 * keyProfile 预设统一播种；这边管**动作 / 编辑**，纯用户自定义、不受预设影响。冲突检测会把
 * 两边的键位一起算进来 —— 它们抢的是同一个物理键盘。
 */
class ShortcutKeysSettingsFragment : PaddingPreferenceFragment() {

    private lateinit var shortcuts: AppPrefs.Shortcuts
    private val keyPrefs = mutableListOf<KeyCapturePreference>()

    private var tabLayout: TabLayout? = null
    private var screens: List<Pair<String, PreferenceScreen>> = emptyList()
    private var selectedTab = 0

    /** 键位行排成双列网格（见 [gridSpanCount]）；说明行与底部按钮仍占满整行。 */
    override val gridSpanCount: Int = 2

    private companion object {
        const val KEY_SELECTED_TAB = "shortcut_selected_tab"

        /** 双列下仍要占满整行的偏好（说明行 + 底部两个按钮），按 preference.key 识别。 */
        val FULL_SPAN_KEYS = setOf("shortcut_intro", "shortcut_apply_preset", "shortcut_reset_all")
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        shortcuts = AppPrefs.getInstance().shortcuts

        // 两个固定 Tab：编辑 = 编辑类（含选字四向），开关 = 开关类（按 ShortcutAction.chord 分组）。
        val fnScreen = preferenceManager.createPreferenceScreen(context)
        val symScreen = preferenceManager.createPreferenceScreen(context)

        // 说明行。两点必须说清：① 只在物理键按下时生效（软键盘点按不经过 onKeyDown）；
        // ② 留空 = 不绑定 —— 未绑定的行显示「无」是有意为之，而不是坏了。
        fnScreen.addPreference(Preference(context).apply {
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
            when (action.chord) {
                ShortcutChord.FN -> fnScreen
                ShortcutChord.SYM -> symScreen
            }.addPreference(capture)
            keyPrefs.add(capture)
        }

        // 恢复推荐键位 + 全部解绑：作用于整页配置（两族动作一起写/一起清），
        // 每个 Tab 底部各放一份入口，免去为按个按钮来回切 Tab。
        listOf(fnScreen, symScreen).forEach { screen ->
            screen.addPreference(presetButton(context))
            screen.addPreference(resetButton(context))
        }

        screens = listOf(
            getString(R.string.shortcut_tab_editing) to fnScreen,
            getString(R.string.shortcut_tab_toggle) to symScreen,
        )
        selectedTab = (savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: 0).coerceIn(0, screens.lastIndex)
        preferenceScreen = screens[selectedTab].second
    }

    /** 「恢复推荐键位」：按当前**键盘预设**播一套推荐动作键（Titan 系 Fn/Sym 分族；黑莓统一 `Alt_R+字母`）。 */
    private fun presetButton(context: Context): Preference = Preference(context).apply {
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
    }

    /** 「全部解绑」：清空本页所有快捷键绑定。 */
    private fun resetButton(context: Context): Preference = Preference(context).apply {
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        tabLayout = createSettingsTabBar(requireContext())
        (root as? ViewGroup)?.addView(tabLayout, 0)
        return root
    }

    /**
     * 双列网格：键位行一格一个；说明行 / 「恢复推荐键位 / 全部解绑」按 key 占满整行。
     * LayoutManager 挂在 RecyclerView 上，Tab 切换只换 adapter，网格设置自然延续。
     */
    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val rv = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        rv.layoutManager = GridLayoutManager(requireContext(), gridSpanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val pref = (rv.adapter as? PreferenceGroupAdapter)?.getItem(position)
                    return if (pref != null && pref.key in FULL_SPAN_KEYS) gridSpanCount else 1
                }
            }
        }
        return rv
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabLayout()
    }

    private fun setupTabLayout() {
        val tl = tabLayout ?: return
        tl.visibility = View.VISIBLE
        tl.removeAllTabs()
        screens.forEach { (title, _) -> tl.addTab(tl.newTab().setText(title)) }
        tl.selectTab(tl.getTabAt(selectedTab))
        tl.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position ?: return
                selectedTab = pos
                preferenceScreen = screens[pos].second
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTab)
    }
}
