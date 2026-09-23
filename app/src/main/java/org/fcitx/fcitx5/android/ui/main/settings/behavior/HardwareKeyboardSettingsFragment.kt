/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import com.google.android.material.tabs.TabLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.SoundScheme
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.createSettingsTabBar
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.KeyCapturePreference
import org.fcitx.fcitx5.android.ui.main.settings.KeyCaptureUi

class HardwareKeyboardSettingsFragment : PaddingPreferenceFragment() {

    private lateinit var hw: AppPrefs.HardwareKeyboard
    private val keyPrefs = mutableListOf<KeyCapturePreference>()

    /** The 巨硬 quick-pick master switch; its summary shows the LIVE key bindings (task: 键位描述). */
    private lateinit var quickPickSwitch: SwitchPreference

    /** The fly-text switches; the swap toggle is only enabled while fly-text itself is on. */
    private lateinit var flyTextSwitch: SwitchPreference
    private lateinit var flyTextSwapSwitch: SwitchPreference

    /**
     * References to the candidate2-5 [KeyCapturePreference] views. Their visibility is driven by
     * the bottom-row quick-pick toggle ([hw.enableCandidateQuickPick]): visible when quick-pick is
     * on (the bottom-row physical keys act as quick-pick shortcuts), hidden when it is off (the
     * keys are cleared so they don't fire on stray presses).
     */
    private val candidateShortcutPrefs = mutableListOf<KeyCapturePreference>()

    private var tabLayout: TabLayout? = null
    private var screens: List<Pair<String, PreferenceScreen>> = emptyList()
    private var selectedTab = 0

    private companion object {
        const val KEY_SELECTED_TAB = "hw_selected_tab"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val profileScreen = preferenceManager.createPreferenceScreen(context)
        val flyTextScreen = preferenceManager.createPreferenceScreen(context)
        val altScreen = preferenceManager.createPreferenceScreen(context)
        val soundScreen = preferenceManager.createPreferenceScreen(context)

        hw = AppPrefs.getInstance().hardwareKeyboard

        // On a fresh install, persist the default (BlackBerry) profile so the individual key
        // bindings match what selecting that preset would produce. No-op once already initialised.
        hw.ensureInitialized()

        // Preset profile dropdown: choosing a profile overrides every individual key binding —
        // and seeds the matching shortcut set (Fn / Alt chords, see HardwareKeyProfiles).
        val profileIds = HardwareKeyProfiles.ids()
        val profileList = ListPreference(context).apply {
            key = hw.keyProfile.key
            title = getString(R.string.hw_key_profile)
            entries = profileIds.map { getString(HardwareKeyProfiles.labelResFor(it)) }.toTypedArray()
            entryValues = profileIds.toTypedArray()
            setDefaultValue(hw.keyProfile.getValue())
            value = hw.keyProfile.getValue()
            summary = "%s"
            isIconSpaceReserved = false
        }
        profileList.setOnPreferenceChangeListener { _, newValue ->
            applyProfile(newValue as String)
            true
        }
        profileScreen.addPreference(profileList)

        // 底排物理键快速选字开关：仅控制"物理键是否选词"，与候选栏排列顺序无关
        // （排列顺序在"候选栏选项 → Candidate arrangement"中设置）。
        // 摘要动态显示当前配置的实际键位（随预设/开关联动刷新），不再硬编码某一版的键位。
        quickPickSwitch = SwitchPreference(context).apply {
            key = hw.enableCandidateQuickPick.key
            title = getString(R.string.hw_enable_candidate_quick_pick)
            setDefaultValue(hw.enableCandidateQuickPick.getValue())
            isChecked = hw.enableCandidateQuickPick.getValue()
            isIconSpaceReserved = false
        }
        updateQuickPickSummary()
        quickPickSwitch.setOnPreferenceChangeListener { _, newValue ->
            applyQuickPick(newValue as Boolean)
            true
        }
        profileScreen.addPreference(quickPickSwitch)

        // —— 飞字独立 Tab ——
        // 飞字不依赖任何硬件能力闸门：任意设备都开放。无键盘触摸面的机型只是收不到触摸事件、
        // 功能自然不触发，但设置项始终可用（service 侧 flyTextOn 也不再以此能力做门）。
        flyTextSwitch = SwitchPreference(context).apply {
            key = hw.keyboardFlyText.key
            title = getString(R.string.hw_keyboard_flytext)
            summary = getString(R.string.hw_keyboard_flytext_summary)
            setDefaultValue(hw.keyboardFlyText.getValue())
            isChecked = hw.keyboardFlyText.getValue()
            isIconSpaceReserved = false
        }
        flyTextSwapSwitch = SwitchPreference(context).apply {
            key = hw.keyboardFlyTextSwapPage.key
            title = getString(R.string.hw_flytext_swap_page)
            summary = getString(R.string.hw_flytext_swap_page_summary)
            setDefaultValue(hw.keyboardFlyTextSwapPage.getValue())
            isChecked = hw.keyboardFlyTextSwapPage.getValue()
            isIconSpaceReserved = false
            // Sub-toggle only meaningful while fly-text is on.
            isEnabled = hw.keyboardFlyText.getValue()
        }
        // Sensitivity: scales every fly-text travel threshold (50% = twice the travel needed,
        // 150% = a third less). Same manual enable/disable wiring as the swap toggle above —
        // Preference.dependency cannot be used while building the screen dynamically.
        val flyTextSensitivityPref = DialogSeekBarPreference(context).apply {
            key = hw.keyboardFlyTextSensitivity.key
            title = getString(R.string.hw_flytext_sensitivity)
            dialogTitle = getString(R.string.hw_flytext_sensitivity)
            setDefaultValue(hw.keyboardFlyTextSensitivity.defaultValue)
            min = 50
            max = 150
            step = 5
            unit = "%"
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            isIconSpaceReserved = false
            isSingleLineTitle = false
            isEnabled = hw.keyboardFlyText.getValue()
        }
        flyTextScreen.addPreference(flyTextSwitch)
        flyTextScreen.addPreference(flyTextSwapSwitch)
        flyTextScreen.addPreference(flyTextSensitivityPref)

        // Typing-guard window: how long after a hardware key event a surface contact is treated
        // as typing residue (0 = guard off). Same manual enable/disable wiring as above.
        val flyTextGuardPref = DialogSeekBarPreference(context).apply {
            key = hw.keyboardFlyTextGuardMs.key
            title = getString(R.string.hw_flytext_guard_ms)
            dialogTitle = getString(R.string.hw_flytext_guard_ms)
            setDefaultValue(hw.keyboardFlyTextGuardMs.defaultValue)
            min = 0
            max = 1000
            step = 50
            unit = "ms"
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            isIconSpaceReserved = false
            isSingleLineTitle = false
            isEnabled = hw.keyboardFlyText.getValue()
        }
        flyTextScreen.addPreference(flyTextGuardPref)
        flyTextSwitch.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            flyTextSwapSwitch.isEnabled = on
            flyTextSensitivityPref.isEnabled = on
            flyTextGuardPref.isEnabled = on
            true
        }

        // Master toggle: double-tap left Alt to latch the Alt modifier.
        val altLatchSwitch = SwitchPreference(context).apply {
            key = hw.altLatchEnabled.key
            title = getString(R.string.hw_alt_latch)
            summary = getString(R.string.hw_alt_latch_summary)
            setDefaultValue(hw.altLatchEnabled.getValue())
            isChecked = hw.altLatchEnabled.getValue()
            isIconSpaceReserved = false
        }
        altScreen.addPreference(altLatchSwitch)

        // Which key double-tap latches Alt (only relevant while the master toggle is on).
        // NOTE: we cannot use Preference.dependency here — when the screen is built dynamically
        // in onCreatePreferences, the dependency lookup runs before the manager's screen tree is
        // wired up and throws IllegalStateException. Drive enable/disable manually instead.
        val altLatchKeyPref = KeyCapturePreference(context).apply {
            key = hw.altLatchKey.key
            title = getString(R.string.hw_alt_latch_key)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(hw.altLatchKey.getValue())
            summaryProvider = KeyCapturePreference.KeySummaryProvider
        }
        altLatchKeyPref.isEnabled = altLatchSwitch.isChecked
        altLatchSwitch.setOnPreferenceChangeListener { _, newValue ->
            altLatchKeyPref.isEnabled = newValue as Boolean
            true
        }
        altScreen.addPreference(altLatchKeyPref)
        keyPrefs.add(altLatchKeyPref)

        // Alt+Delete / Alt+Backspace deletes the whole line (terminal kill-line) instead of a
        // single character. Default OFF preserves the classic mobile delete-one-char behaviour.
        val altDeleteLineSwitch = SwitchPreference(context).apply {
            key = hw.altDeleteLineEnabled.key
            title = getString(R.string.hw_alt_delete_line)
            summary = getString(R.string.hw_alt_delete_line_summary)
            setDefaultValue(hw.altDeleteLineEnabled.getValue())
            isChecked = hw.altDeleteLineEnabled.getValue()
            isIconSpaceReserved = false
        }
        altScreen.addPreference(altDeleteLineSwitch)

        // Long-press a physical key to input its keycap symbol (BlackBerry-style). Default ON.
        val longPressSymbolSwitch = SwitchPreference(context).apply {
            key = hw.longPressSymbolEnabled.key
            title = getString(R.string.hw_long_press_symbol)
            summary = getString(R.string.hw_long_press_symbol_summary)
            setDefaultValue(hw.longPressSymbolEnabled.getValue())
            isChecked = hw.longPressSymbolEnabled.getValue()
            isIconSpaceReserved = false
        }
        soundScreen.addPreference(longPressSymbolSwitch)

        // Long-press duration (ms) before a held key commits its keycap symbol. Only meaningful
        // while the switch above is on, so disable it unless the switch is checked.
        val longPressSymbolThresholdPref = DialogSeekBarPreference(context).apply {
            key = hw.longPressSymbolThreshold.key
            title = getString(R.string.hw_long_press_symbol_threshold)
            dialogTitle = getString(R.string.hw_long_press_symbol_threshold)
            setDefaultValue(hw.longPressSymbolThreshold.defaultValue)
            min = 300
            max = 1000
            step = 50
            unit = "ms"
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        longPressSymbolThresholdPref.isEnabled = longPressSymbolSwitch.isChecked
        longPressSymbolSwitch.setOnPreferenceChangeListener { _, newValue ->
            longPressSymbolThresholdPref.isEnabled = newValue as Boolean
            true
        }
        soundScreen.addPreference(longPressSymbolThresholdPref)

        // Play the keyboard click sound for physical key presses. Default ON. This switch is the
        // whole gate for physical keys (the on-screen keyboard's sound mode does not apply); the
        // volume below is physical-keyboard specific.
        val keySoundSwitch = SwitchPreference(context).apply {
            key = hw.keySoundEnabled.key
            title = getString(R.string.hw_key_sound)
            summary = getString(R.string.hw_key_sound_summary)
            setDefaultValue(hw.keySoundEnabled.getValue())
            isChecked = hw.keySoundEnabled.getValue()
            isIconSpaceReserved = false
        }
        soundScreen.addPreference(keySoundSwitch)

        // Physical key sound volume (0 = system default). Same manual enable/disable wiring as
        // altLatchKeyPref — Preference.dependency cannot be used while building the screen here.
        val keySoundVolumePref = DialogSeekBarPreference(context).apply {
            key = hw.keySoundVolume.key
            title = getString(R.string.hw_key_sound_volume)
            dialogTitle = getString(R.string.hw_key_sound_volume)
            defaultLabel = getString(R.string.system_default)
            // MUST be the preference's constant default (0 = system default), NOT the current
            // value: DialogSeekBarPreference.textForValue() renders `defaultLabel` whenever
            // value == default, so seeding it with the persisted value makes a saved 50% show up
            // as "system default" on the next visit (and rebinds the dialog's "default" button to
            // that value). This mirrors ManagedPreferenceUi.SeekBarInt, which passes the constant.
            setDefaultValue(hw.keySoundVolume.defaultValue)
            min = 0
            max = 100
            step = 1
            unit = "%"
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        keySoundVolumePref.isEnabled = keySoundSwitch.isChecked
        keySoundSwitch.setOnPreferenceChangeListener { _, newValue ->
            keySoundVolumePref.isEnabled = newValue as Boolean
            true
        }
        soundScreen.addPreference(keySoundVolumePref)

        // Keypress sound flavour (timbre). Lives on the physical-keyboard sound tab, but the chosen
        // scheme is a single global selection also shared by the on-screen keyboard — both reach it
        // through InputFeedbacks. entryValues are the enum constant names (how
        // ManagedPreferenceEnum serialises), entries are the user-facing strings.
        val soundSchemePref = ListPreference(context).apply {
            key = hw.soundScheme.key
            title = getString(R.string.sound_scheme)
            entries = arrayOf(
                getString(R.string.sound_scheme_classic),
                getString(R.string.sound_scheme_crisp),
                getString(R.string.sound_scheme_muffled),
                getString(R.string.sound_scheme_soft),
                getString(R.string.sound_scheme_piano),
                getString(R.string.sound_scheme_telegraph),
                getString(R.string.sound_scheme_woodfish),
                getString(R.string.sound_scheme_abacus),
                getString(R.string.sound_scheme_mechanical),
                getString(R.string.sound_scheme_silent)
            )
            entryValues = arrayOf(
                SoundScheme.Classic.name,
                SoundScheme.Crisp.name,
                SoundScheme.Muffled.name,
                SoundScheme.Soft.name,
                SoundScheme.Piano.name,
                SoundScheme.Telegraph.name,
                SoundScheme.Woodfish.name,
                SoundScheme.Abacus.name,
                SoundScheme.Mechanical.name,
                SoundScheme.Silent.name
            )
            // MUST be the enum's NAME, not the enum instance: ListPreference.onSetInitialValue
            // casts the default straight to String ((String) mDefaultValue), while
            // ManagedPreference.PStringLike<T>.defaultValue is typed as T itself (enumList yields
            // the enum constant). Passing it as-is throws ClassCastException out of
            // onAttachedToHierarchy — and because createPreferenceScreen() attaches the screen
            // immediately, addPreference() below triggers it right here. It only fires while this key
            // has never been persisted (fresh install) and silently "heals" once the user picks a
            // scheme, which is why it is so hard to catch. The name is also what enumList's codec
            // stores (decode = enumValueOf(raw)), so it matches entryValues / the persisted value.
            setDefaultValue(hw.soundScheme.defaultValue.name)
            value = hw.soundScheme.getValue().name
            summary = "%s"
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        soundScreen.addPreference(soundSchemePref)

        // Build the per-key preferences. candidate2-5 are remembered separately so the display-mode
        // handler can flip their visibility without disturbing candidate1 (first-pick, always shown).
        // Use key string (not `===` reference) to identify the four shortcut prefs — this avoids any
        // generic-vs-concrete type mismatches that can hide the list when listOf infers a wider type
        // for the iterated `pref`.
        listOf(
            hw.candidate1Key to R.string.candidate_key_1,
            hw.candidate2Key to R.string.candidate_key_2,
            hw.candidate3Key to R.string.candidate_key_3,
            hw.candidate4Key to R.string.candidate_key_4,
            hw.candidate5Key to R.string.candidate_key_5,
            hw.pageNextKey to R.string.candidate_page_next,
            hw.pagePrevKey to R.string.candidate_page_prev,
            hw.toggleImeKey to R.string.hw_toggle_ime,
            hw.pickerKey to R.string.hw_show_picker,
        ).forEach { (pref, titleRes) ->
            val capture = KeyCapturePreference(context).apply {
                key = pref.key
                title = getString(titleRes)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setDefaultValue(pref.getValue())
                summaryProvider = KeyCapturePreference.KeySummaryProvider
            }
            profileScreen.addPreference(capture)
            keyPrefs.add(capture)
            if (pref.key == hw.candidate2Key.key || pref.key == hw.candidate3Key.key ||
                pref.key == hw.candidate4Key.key || pref.key == hw.candidate5Key.key) {
                candidateShortcutPrefs.add(capture)
            }
        }

        // 符号键盘快捷键（Sym 键绑定）：从上面的按键循环里单独提出来，
        // 以便把「Sym 键首选面板」直接挂在它下方，两者配套更直观。
        val symbolPickerPref = KeyCapturePreference(context).apply {
            key = hw.symbolPickerKey.key
            title = getString(R.string.hw_symbol_picker)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(hw.symbolPickerKey.getValue())
            summaryProvider = KeyCapturePreference.KeySummaryProvider
        }
        profileScreen.addPreference(symbolPickerPref)
        keyPrefs.add(symbolPickerPref)

        // Apply the persisted quick-pick state's visibility to candidate2-5 BEFORE returning, so
        // the screen never briefly shows rows that the current state says should be hidden.
        setCandidateShortcutVisibility(hw.enableCandidateQuickPick.getValue())

        screens = listOf(
            getString(R.string.cat_hw_profile) to profileScreen,
            getString(R.string.cat_hw_flytext) to flyTextScreen,
            getString(R.string.cat_hw_alt) to altScreen,
            getString(R.string.cat_hw_sound_symbol) to soundScreen
        )
        selectedTab = (savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: 0).coerceIn(0, screens.lastIndex)
        preferenceScreen = screens[selectedTab].second
    }

    /**
     * Override all individual key bindings with the selected preset, then refresh the summaries.
     *
     * 顺带把「快捷键」那套动作键也按同一机型播一遍（修饰键随机器不同：Titan 系是 Fn，
     * BlackBerry 是 Alt）—— 只播物理键位会留下一半配置。
     */
    private fun applyProfile(name: String) {
        HardwareKeyProfiles.applyProfile(name, AppPrefs.getInstance())
        keyPrefs.forEach { it.refresh() }
        setCandidateShortcutVisibility(hw.enableCandidateQuickPick.getValue())
        updateQuickPickSummary()
    }

    /**
     * Enable/disable the bottom-row physical-key quick-pick:
     * - enabled  → re-seed candidate2-5 from the currently selected [hw.keyProfile] (BlackBerry
     *   or TT2) and show the four preference rows.
     * - disabled → clear candidate2-5 so the bottom-row physical keys no longer pick candidates
     *   at runtime, and hide the four preference rows.
     *
     * (candidate1 / Space still selects the first-pick word regardless of this toggle — the toggle
     * only governs the four extra candidate2-5 shortcuts, not the arrangement order.)
     */
    private fun applyQuickPick(enabled: Boolean) {
        if (enabled) {
            HardwareKeyProfiles.applyCandidateKeys(hw.keyProfile.getValue(), hw)
        } else {
            HardwareKeyProfiles.clearCandidateKeys(hw)
        }
        setCandidateShortcutVisibility(enabled)
        keyPrefs.forEach { it.refresh() }
        updateQuickPickSummary()
    }

    /**
     * 巨硬模式摘要显示当前实际配置的键位（第 1~5 候选依次列出），随预设切换与快速选字开关
     * 联动刷新。键名渲染复用 [KeyCaptureUi.formatKey]（伪按键 Sym/NavBack 等有本地化名），
     * 与下方各行 KeyCapturePreference 的摘要渲染保持一致；未绑定的键显示为"无"。
     */
    private fun updateQuickPickSummary() {
        val keys = listOf(
            hw.candidate1Key, hw.candidate2Key, hw.candidate3Key,
            hw.candidate4Key, hw.candidate5Key
        ).joinToString("、") { KeyCaptureUi.formatKey(requireContext(), it.getValue()) }
        quickPickSwitch.summary = getString(R.string.hw_enable_candidate_quick_pick_summary, keys)
    }

    private fun setCandidateShortcutVisibility(visible: Boolean) {
        candidateShortcutPrefs.forEach { it.isVisible = visible }
        // androidx preference 1.2.1: Preference.setVisible fires OnPreferenceChangeInternalListener
        // .onPreferenceVisibilityChange → onPreferenceHierarchyChange, which rebuilds the
        // adapter's visible-preferences list and calls notifyDataSetChanged. So setting
        // isVisible is enough — no extra notifyChanged() is needed (and that method is
        // package-private anyway, not callable from the fragment).
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        tabLayout = createSettingsTabBar(requireContext())
        (root as? ViewGroup)?.addView(tabLayout, 0)
        return root
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
