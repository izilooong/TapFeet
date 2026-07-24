/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidateArrangementMode
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // there's some feedback that this workaround is no longer necessary on Origin OS 4, which based on Android 14
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DeviceUtil.isVivoOriginOS
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                0,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                0,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val expandToolbarByDefault =
            switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", false)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) { showVoiceInputButton.getValue() }

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Down
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.None
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val langSwitchKeyBehavior = enumList(
            R.string.lang_switch_key_behavior,
            "lang_switch_key_behavior",
            LangSwitchBehavior.Enumerate
        ) { showLangSwitchKey.getValue() }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                40,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                49,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                0,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                0,
                0,
                300,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                0,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                0,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                5,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                5,
                4,
                12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.Disabled
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }

    }

    inner class CandidateBar :
        ManagedPreferenceCategory(R.string.candidate_bar_options, sharedPreferences) {

        // 候选栏的排列顺序（仅影响视觉排布，不影响物理键快速选字）：
        //   Macrohard 巨硬 [4-2-1-3-5]（首选字居中向两侧展开）
        //   Linear    普通 [1-2-3-4-5]（从左到右线性）
        // 物理键快速选字的开关在 HardwareKeyboard.enableCandidateQuickPick。
        val arrangementMode = enumList(
            R.string.candidate_arrangement_mode,
            "candidate_arrangement_mode",
            CandidateArrangementMode.Macrohard
        )

        // 横向候选词的填充模式：从不填充 / 按需填充（默认）/ 总是填充
        // 同时在触摸虚拟键盘的横向候选条（HorizontalCandidateComponent）
        // 与 Q25 物理键盘的悬浮候选窗（PagedCandidatesUi）中生效。
        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )

        val showCandidateIndex = switch(
            R.string.show_candidate_index,
            "show_candidate_index",
            true
        )

        val candidateIndexFontSize = int(
            R.string.candidate_index_font_size,
            "candidate_index_font_size",
            10,
            6,
            16,
            "sp"
        ) { showCandidateIndex.getValue() }

        val candidateTextFontSize = int(
            R.string.candidate_text_font_size,
            "candidate_text_font_size",
            20,
            10,
            32,
            "sp"
        )


        val showCandidateFlyAnimation = switch(
            R.string.show_candidate_fly_animation,
            "show_candidate_fly_animation",
            true
        )
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            10,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            -1,
            Int.MAX_VALUE,
            "s"
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    inner class HardwareKeyboard :
        ManagedPreferenceCategory(R.string.hardware_keyboard, sharedPreferences) {
        // Selected key-layout preset: "blackberry" or "tt2". Choosing a preset in the settings
        // screen overrides all individual key bindings with that preset's values.
        val keyProfile = string("hw_key_profile", "blackberry")

        // 仅控制底排物理键（candidate2-5）是否作为快速选字快捷键，与候选栏的排列顺序无关。
        // 开启（默认）：candidate2-5 按当前 keyProfile 绑定物理键，可快速选字。
        // 关闭：清空 candidate2-5 键值，底排物理键不再触发选字（candidate1/Space 仍可选首选字）。
        // 排列顺序由 CandidateBar.arrangementMode 决定。
        val enableCandidateQuickPick = switch(
            R.string.hw_enable_candidate_quick_pick,
            "hw_enable_candidate_quick_pick",
            true,
            R.string.hw_enable_candidate_quick_pick_summary
        )

        // Double-tap the latch key to lock the Alt modifier. Default ON.
        val altLatchEnabled = bool("hw_alt_latch_enabled", true)
        // Which physical key, when double-tapped, latches (locks) the Alt modifier.
        // fcitx5 portableString. Default value left empty: the real default ("Alt_L" for blackberry,
        // "Alt_R" for tt2) is owned by [HardwareKeyProfiles] and written by [ensureInitialized].
        val altLatchKey = string("hw_alt_latch_key", "")

        // fcitx5 Key portableString for each shortcut (e.g. "Alt+space", "dollar", "Shift_L").
        // The BlackBerry SYM key has no fcitx5 KeySym and is stored as the special string "Sym".
        //
        // Default values are intentionally empty: the real blackberry/tt2 defaults live in
        // [HardwareKeyProfiles.blackberry] / [HardwareKeyProfiles.tt2] (single source of truth
        // for what "the blackberry preset" means), and [ensureInitialized] writes them into
        // SharedPreferences on first run. Hard-coding the same strings here as fallback would
        // let the two definitions drift apart — which is exactly the bug that motivated moving
        // the values out (the page-prev key used to default to "grave" instead of "Alt+grave").
        val candidate1Key = string("hw_candidate_1_key", "")
        val candidate2Key = string("hw_candidate_2_key", "")
        val candidate3Key = string("hw_candidate_3_key", "")
        val candidate4Key = string("hw_candidate_4_key", "")
        val candidate5Key = string("hw_candidate_5_key", "")
        val pageNextKey = string("hw_candidate_page_next_key", "")
        val pagePrevKey = string("hw_candidate_page_prev_key", "")
        val symbolPickerKey = string("hw_symbol_picker_key", "")
        // Global key actions (extracted from candidate1's Alt/Shift combos so they can be rebound).
        // Empty string means "not bound".
        val toggleImeKey = string("hw_toggle_ime_key", "")
        val pickerKey = string("hw_picker_key", "")

        /**
         * The string() defaults above are intentionally empty placeholders. The real
         * blackberry/tt2 values live in [HardwareKeyProfiles] (single source of truth) and
         * are written into SharedPreferences here on the first run, so the keys end up with
         * the same values whether the user just installed the app or selected the preset
         * from the settings dropdown. Previously these defaults were hard-coded twice (once
         * in the string() defaults, once in blackberry()/tt2()) and they drifted — the
         * page-prev key once defaulted to "grave" instead of "Alt+grave", deadening the
         * next-page key until the user re-selected a preset.
         *
         * Guard: only seed when NONE of the hardware-keyboard bindings have ever been persisted.
         * If any key already exists, the user has configured them (or upgraded from an older build
         * that already stored them), so we leave their values untouched — never overwrite.
         */
        private val seededKeys = listOf(
            keyProfile, candidate1Key, candidate2Key, candidate3Key, candidate4Key, candidate5Key,
            pageNextKey, pagePrevKey, symbolPickerKey, toggleImeKey, pickerKey, altLatchKey
        )

        fun ensureInitialized() {
            if (seededKeys.any { sharedPreferences.contains(it.key) }) return
            HardwareKeyProfiles.applyProfile(keyProfile.getValue(), this)
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val hardwareKeyboard = HardwareKeyboard().register()
    val candidates = Candidates().register()
    val candidateBar = CandidateBar().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()
   

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                candidateBar,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            // Seed the default hardware-keyboard preset on a fresh install so the per-key bindings
            // match what selecting that preset would produce (avoids a dead next-page key on first run).
            getInstance().hardwareKeyboard.ensureInitialized()
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}