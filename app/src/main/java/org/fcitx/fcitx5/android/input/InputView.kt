/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.prefs.SymFirstTarget
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidateArrangementMode
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.HiddenKeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.CustomKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.normalizeKeyString
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val hiddenKeyboardWindow = HiddenKeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            return resources.displayMetrics.heightPixels * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    private fun keyboardWindowHeightPx(): Int {
        if (!windowManager.isKeyboardWindowVisible()) return 0
        // 自定义一行键盘：窗口高度压到单行（主键盘 TextKeyboard 共 4 行，取 1/4）
        return if (keyboardWindow.isCustomKeyboardActive) keyboardHeightPx / TextKeyboard.Layout.size
        else keyboardHeightPx
    }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    // ---- Hardware shortcut key caching ----------------------------------------
    // Configured shortcut strings (e.g. "Alt+space", "Shift_L", "Sym") only change when
    // the user edits settings, yet `handleHardwareCandidateShortcut` runs on EVERY physical
    // key down. Parsing them via `Key.parse(normalizeKeyString(...))` on each keystroke is
    // wasted work, so we memoize the parsed `Key` and invalidate the cache on pref change.
    private sealed interface ParsedKey {
        object Sym : ParsedKey
        data class Ref(val key: Key) : ParsedKey
    }

    private val parsedKeyCache = mutableMapOf<String, ParsedKey>()
    private var preciseShortcutsCache = mutableMapOf<Pair<Int, CandidateArrangementMode>, List<ShortcutRule>>()
    private var wideShortcutsCache = mutableMapOf<CandidateArrangementMode, List<ShortcutRule>>()
    // All configured shortcut keys, parsed once and memoized. `isHardwareShortcutKey` runs on
    // EVERY physical key down, and re-parsing 10 key strings (incl. 10 SharedPreferences reads)
    // each time is pure waste. Invalidated together with the other caches below.
    private var shortcutKeysCache: List<ParsedKey>? = null

    /**
     * Memoized list of every configured hardware shortcut key (candidates 1-5, symbol picker,
     * paging, toggle-IME, picker). Used by [isHardwareShortcutKey], which runs on every physical
     * key down — building this list once and reusing it avoids 10 SharedPreferences reads + 10
     * cache lookups per keystroke. Invalidated on pref change via [onHardwareKeyChangeListener].
     */
    private fun shortcutParsedKeys(): List<ParsedKey> {
        shortcutKeysCache?.let { return it }
        val hw = hardwareKeyboardPrefs
        val keys = listOf(
            hw.candidate1Key, hw.candidate2Key, hw.candidate3Key, hw.candidate4Key, hw.candidate5Key,
            hw.symbolPickerKey, hw.pageNextKey, hw.pagePrevKey, hw.toggleImeKey, hw.pickerKey,
        ).mapNotNull { parseKeyString(it.getValue()) }
        shortcutKeysCache = keys
        return keys
    }

    private fun parseKeyString(keyString: String): ParsedKey? {
        if (keyString.isEmpty()) return null
        return parsedKeyCache.getOrPut(keyString) {
            if (keyString == "Sym") ParsedKey.Sym
            else ParsedKey.Ref(Key.parse(normalizeKeyString(keyString)))
        }
    }

    private fun matchesParsedKey(event: KeyEvent, parsed: ParsedKey?): Boolean {
        if (parsed == null) return false
        return when (parsed) {
            ParsedKey.Sym -> event.keyCode == KeyEvent.KEYCODE_SYM ||
                event.keyCode == KeyEvent.KEYCODE_PICTSYMBOLS
            is ParsedKey.Ref -> matchesKey(event, parsed.key)
        }
    }

    @Keep
    private val onHardwareKeyChangeListener = ManagedPreferenceProvider.OnChangeListener { _ ->
        parsedKeyCache.clear()
        preciseShortcutsCache.clear()
        wideShortcutsCache.clear()
        shortcutKeysCache = null
    }

    private val hardwareKeyboardPrefs = AppPrefs.getInstance().hardwareKeyboard

    // Hold the ManagedPreference<String> directly (no `by` delegate) so we can call
    // .getValue() to branch on the active candidate display mode (巨硬 vs 普通) when picking
    // a candidate via candidate1Key (Space). With `by`, the property would be the unwrapped
    // String and the method would not resolve — see the same fix in HorizontalCandidateComponent.
    private val candidateArrangementModePref = AppPrefs.getInstance().candidateBar.arrangementMode

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        // 布局切换（如进出自定义一行键盘）后刷新键盘窗口高度与 IME 触摸区域
        keyboardWindow.onLayoutSwitched = {
            updateKeyboardSize()
            service.requestInsetsUpdate()
            // 物理键盘模式下，从符号/自定义会话返回主键盘（TextKeyboard）时收起虚拟键盘。
            // 自 Sym 改为「符号↔自定义」二态循环后，Sym 不再回主键盘，故在此兜底收起，
            // 覆盖符号面板 ABC 键、自定义键盘下滑返回等回主键盘路径。
            if (physicalKeyboardMode && isInputViewRevealed() &&
                keyboardWindow.currentLayoutName == TextKeyboard.Name) {
                visibility = View.GONE
            }
        }
        windowManager.addEssentialWindow(hiddenKeyboardWindow, createView = true)
        windowManager.registerKeyboardVisibilityWindows(
            KeyboardWindow,
            HiddenKeyboardWindow,
            visible = false
        )
        windowManager.setKeyboardVisibilityListener {
            if (windowManager.view.layoutParams != null) {
                windowManager.view.updateLayoutParams {
                    height = keyboardWindowHeightPx()
                }
            }
        }
        windowManager.addEssentialWindow(symbolPicker)
        // 让 KeyboardWindow 持有符号选择器引用，以便在重新进入输入状态时恢复符号态
        keyboardWindow.symbolPickerWindow = symbolPicker
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // keep the toolbar visible and collapse the button area by default
        windowManager.attachWindow(KeyboardWindow)

        // Whenever a window is (re)attached inside this InputView — the symbol picker, or the
        // number/letter keyboard switched to from within the picker — the IME's touchable insets
        // may need to be recomputed (see FcitxInputMethodService.onComputeInsets). Force it.
        // 同时刷新键盘窗口高度：窗口切换（如自定义一行键盘 ↔ 符号选择器）后高度必须随之变化。
        windowManager.onWindowAttached = {
            updateKeyboardSize()
            service.requestInsetsUpdate()
        }

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        hardwareKeyboardPrefs.registerOnChangeListener(onHardwareKeyChangeListener)
    }

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = keyboardWindowHeightPx()
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        val candidateListData = service.lastCandidateListData
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.CandidateListEvent(candidateListData),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.PagedCandidateEvent -> {
                broadcaster.onPagedCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    fun onCommitText(text: String) {
        broadcaster.onCommitText(text)
    }

    fun onAltLatchChanged(locked: Boolean) {
        kawaiiBar.onAltLatchChanged(locked)
    }

    fun onSystemAltStickyChanged(sticky: Boolean) {
        kawaiiBar.onSystemAltStickyChanged(sticky)
    }

    // fcitx5 modifier keysym range: Shift_L (0xffe1) through Hyper_R (0xffee).
    // A modifier key sets its own state when pressed, so it must be matched by keysym only.
    private fun isModifierKeySym(sym: Int): Boolean = sym in 0xffe1..0xffee

    /**
     * Match a [KeyEvent] against a stored key string (fcitx5 portableString, e.g. "Alt+space",
     * "dollar", "Shift_L", or the special "Sym" string for the BlackBerry SYM key).
     *
     * Uses [KeySym.fromKeyEvent] (character identity) so symbol keys like `$` are matched by the
     * character they produce, not by an unreliable Android keyCode. Combos (keys with a modifier,
     * e.g. "Alt+grave") match the modifier exactly via [rawModifierStates]; plain keys keep the
     * tolerant stripping of [KeyStates.fromKeyEvent].
     */
    /**
     * Extract the modifier state directly from the event's pressed modifiers, WITHOUT the
     * number/symbol-key stripping that [KeyStates.fromKeyEvent] applies. Needed so that combos like
     * `Alt+grave` or `Alt+$` match exactly — [KeyStates.fromKeyEvent] would otherwise drop the Alt
     * modifier for symbol keys and the combo could never fire. CapsLock/NumLock are masked out so
     * they don't cause spurious mismatches.
     */
    private fun rawModifierStates(event: KeyEvent): KeyStates {
        var s = KeyState.NoState.state
        if (event.isAltPressed) s = s or KeyState.Alt.state
        if (event.isCtrlPressed) s = s or KeyState.Ctrl.state
        if (event.isShiftPressed) s = s or KeyState.Shift.state
        if (event.isMetaPressed) s = s or KeyState.Meta.state
        return KeyStates(s and KeyState.SimpleMask.state)
    }

    private fun matchesKey(event: KeyEvent, key: Key): Boolean {
        if (key.sym == 0) return false
        // Match by the physical key's keysym OR the character it produces. We must also accept the
        // keyCode-derived keysym because holding a modifier (e.g. Alt) can change event.unicodeChar
        // into a composed character, which would otherwise make the sym comparison fail for symbol
        // keys like grave (`) and break combos such as "Alt+grave". Character keys whose keyCode is
        // unreliable across layouts (e.g. `$`) still match via event.unicodeChar.
        val symFromKeyCode = FcitxKeyMapping.keyCodeToSym(event.keyCode)
        val symMatches = symFromKeyCode == key.sym ||
                (event.unicodeChar != 0 && event.unicodeChar == key.sym)
        if (!symMatches) return false
        if (isModifierKeySym(key.sym)) return true
        // A configured COMBO (has modifier, e.g. "Alt+grave") must match the modifier exactly, so use
        // raw states (no stripping). A plain key (no modifier) keeps [KeyStates.fromKeyEvent]'s
        // tolerant stripping, so an Alt-latched press of a number/symbol key still selects the
        // candidate (the original fcitx5-android behaviour).
        val states = if (key.states != 0) rawModifierStates(event) else KeyStates.fromKeyEvent(event)
        return states.toInt() == key.states
    }

    /** Match by KeySym only (any modifiers) — used to detect a physical key regardless of modifiers. */
    private fun isSameKeySymString(event: KeyEvent, keyString: String): Boolean {
        val parsed = parseKeyString(keyString) ?: return false
        if (parsed == ParsedKey.Sym) {
            return event.keyCode == KeyEvent.KEYCODE_SYM || event.keyCode == KeyEvent.KEYCODE_PICTSYMBOLS
        }
        return isSameKeySym(event, (parsed as ParsedKey.Ref).key)
    }

    private fun isSameKeySym(event: KeyEvent, key: Key): Boolean {
        if (key.sym == 0) return false
        val sym = KeySym.fromKeyEvent(event) ?: return false
        return sym.sym == key.sym
    }

    private fun selectCandidateAtVisiblePosition(position: Int): Boolean {
        val count = horizontalCandidate.visibleCandidateCount()
        if (count <= 0 || position !in 0 until count) return false
        val activeIndex = horizontalCandidate.selectionIndexAtVisiblePosition(position) ?: return false
        val vh = horizontalCandidate.view.findViewHolderForAdapterPosition(position) as? CandidateViewHolder
        vh?.let {
            horizontalCandidate.prepareFlyAnimation(it.candidate.text, it.ui.text)
        }
        service.postFcitxJob {
            setCandidatePagingMode(horizontalCandidate.currentCandidatePagingMode())
            if (select(activeIndex)) return@postFcitxJob
            val candidate = horizontalCandidate.candidateAtVisiblePosition(position) ?: return@postFcitxJob
            service.finishComposing()
            service.commitText(candidate.text)
        }
        return true
    }

    // 单条物理键 → 可见位置 的映射规则（键用 fcitx5 portableString 标识，见下方 preciseShortcuts()）。
    private data class ShortcutRule(val parsedKey: ParsedKey?, val position: Int)

    private fun matchesShortcutKey(event: KeyEvent, rule: ShortcutRule): Boolean =
        matchesParsedKey(event, rule.parsedKey)

    // 1~5 候选的精细映射：物理键 → 可见位置。映射取决于候选栏排列模式（巨硬居中展开 / 普通线性），
    // 必须与 CandidateArrangementMode 保持一致，否则物理键会选到错误的候选。
    // candidate1(k1) 始终由 handleHardwareCandidateShortcut 处理为"首选字"，不在此表内。
    private fun preciseShortcuts(count: Int): List<ShortcutRule>? {
        if (count <= 0 || count > 5) return null
        val arrangement = candidateArrangementModePref.getValue()
        preciseShortcutsCache[count to arrangement]?.let { return it }
        val hw = hardwareKeyboardPrefs
        val rules = when (arrangement) {
            CandidateArrangementMode.Macrohard -> {
                // 巨硬：以"居中候选"为基准，左右物理键按相对偏移定位（候选数 2/3/4 时两侧键也能选到对应候选）
                val center = (count - 1) / 2
                mutableListOf<ShortcutRule>().apply {
                    (center - 1).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), it)) }
                    (center + 1).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), it)) }
                    (center - 2).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), it)) }
                    (center + 2).takeIf { it in 0 until count }
                        ?.let { add(ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), it)) }
                }
            }
            CandidateArrangementMode.Linear -> {
                // 普通：候选按 [1,2,3,4,5] 线性排布，物理键直接映射到顺序位置（candidate N → 位置 N-1）
                val keyFor = listOf(
                    hw.candidate2Key to 2,
                    hw.candidate3Key to 3,
                    hw.candidate4Key to 4,
                    hw.candidate5Key to 5
                )
                mutableListOf<ShortcutRule>().apply {
                    keyFor.forEach { (pref, n) ->
                        val pos = n - 1
                        if (pos < count) add(ShortcutRule(parseKeyString(pref.getValue()), pos))
                    }
                }
            }
        }
        preciseShortcutsCache[count to arrangement] = rules
        return rules
    }

    // >5 候选（wide layout）：物理键 → 可见位置，取决于排列模式。
    private fun wideShortcuts(): List<ShortcutRule> {
        val arrangement = candidateArrangementModePref.getValue()
        wideShortcutsCache[arrangement]?.let { return it }
        val hw = hardwareKeyboardPrefs
        val rules = when (arrangement) {
            CandidateArrangementMode.Macrohard -> listOf(
                ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), CandidateUi.BlackBerryLeftSlot),
                ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), CandidateUi.BlackBerryInnerLeftSlot),
                ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), CandidateUi.BlackBerryInnerRightSlot),
                ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), CandidateUi.BlackBerryRightSlot),
            )
            CandidateArrangementMode.Linear -> listOf(
                ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), 1),
                ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), 2),
                ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), 3),
                ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), 4),
            )
        }
        wideShortcutsCache[arrangement] = rules
        return rules
    }

    private fun resolveShortcutPosition(event: KeyEvent, count: Int): Int? {
        preciseShortcuts(count)?.let { rules ->
            for (r in rules) if (matchesShortcutKey(event, r)) return r.position
        }
        if (count > CandidateUi.BlackBerryBottomRowKeyCount) {
            for (r in wideShortcuts()) {
                if (matchesShortcutKey(event, r) && r.position < count) return r.position
            }
        }
        return null
    }

    /**
     * Side-effect-free check: does [event] match any configured hardware shortcut key
     * (candidate / symbol / paging / global action)?
     *
     * Used by the Alt-latch logic in [FcitxInputMethodService] to detect when the latch trigger
     * key collides with a selection key — so a single press can still select instead of being
     * swallowed by latching. Does NOT perform any selection; it only reads the current config and
     * compares key syms, so it is safe to call from the key-down dispatch path.
     */
    fun isHardwareShortcutKey(event: KeyEvent): Boolean {
        return shortcutParsedKeys().any { matchesParsedKey(event, it) }
    }

    fun handleHardwareCandidateShortcut(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // 全局动作（可配置快捷键）：切换输入法 / 显示输入法选择器。
        // 放在最前，确保无论候选窗是否显示都能触发。
        if (handleHardwareGlobalAction(event)) return true

        // 物理键盘模式下，本 InputView 的水平候选条已隐藏，活跃的候选面是浮动窗口
        // （CandidatesView）。此处若继续走下方的"选字 / 翻页"分支，会经
        // selectCandidateAtVisiblePosition() 调用 setCandidatePagingMode(0)（bulk 模式），
        // 把引擎的候选分页模式从浮动窗口依赖的 paged(1) 切回 bulk(0)。结果浮动窗口再也
        // 收不到 PagedCandidateEvent：表现为"候选栏永远显示同一组、上下页点击无反应"，
        // 但底层选字（引擎状态正确）仍然有效。
        // 物理键盘模式的选字 / 翻页 / 符号键已由浮动窗口处理，这里直接返回，交给浮动窗口，
        // 避免污染引擎分页模式。
        if (physicalKeyboardMode) return false

        val hw = hardwareKeyboardPrefs
        val c1 = hw.candidate1Key.getValue()
        val c1Parsed = parseKeyString(c1)
        val candidate1HasModifier = (c1Parsed as? ParsedKey.Ref)?.key?.states != 0

        // candidate1 组合键（配置带 modifier）：精确匹配后直接选居中候选（优先于符号切换）
        if (candidate1HasModifier && matchesParsedKey(event, c1Parsed)) {
            if (kawaiiBar.isCandidateUiShowing()) {
                val count = horizontalCandidate.visibleCandidateCount()
                if (count > 0) return selectCandidateAtVisiblePosition((count - 1) / 2)
            }
        }

        if (handleHardwareSymToggle(event)) return true
        if (!kawaiiBar.isCandidateUiShowing()) return false
        if (handleHardwareCandidatePaging(event)) return true

        val count = horizontalCandidate.visibleCandidateCount()
        if (count <= 0) return false

        // Plain candidate1 (no combo modifier): selects the "first-pick" candidate.
        // The visible position of the first-pick depends on the display mode:
        //  - Macrohard: candidates are laid out centered/outward, so the first-pick sits at the
        //    middle visible position — i.e. (count - 1) / 2.
        //  - Linear: candidates are laid out left-to-right, so the first-pick sits at position 0.
        // Reading the preference on every press is fine — it is a single SharedPreferences get
        // and keeps the picker stateless against mode changes that happen in the settings screen.
        if (!candidate1HasModifier && isSameKeySymString(event, c1)) {
            val firstPickPosition = when (
                candidateArrangementModePref.getValue()
            ) {
                CandidateArrangementMode.Macrohard -> (count - 1) / 2
                CandidateArrangementMode.Linear -> 0
            }
            return selectCandidateAtVisiblePosition(firstPickPosition)
        }

        val position = resolveShortcutPosition(event, count) ?: return false
        return selectCandidateAtVisiblePosition(position)
    }

    // 全局动作：可配置的快捷键（默认 Alt+space 切换输入法、Shift+space 显示输入法选择器）。
    // 配置为空串表示未绑定。这两个动作原先硬绑在 candidate1Key 的 Alt/Shift 组合上，现独立出来。
    private fun handleHardwareGlobalAction(event: KeyEvent): Boolean {
        val hw = hardwareKeyboardPrefs
        val toggleKey = hw.toggleImeKey.getValue()
        val pickerKeyStr = hw.pickerKey.getValue()
        if (toggleKey.isNotEmpty() && matchesParsedKey(event, parseKeyString(toggleKey))) {
            service.postFcitxJob { toggleIme() }
            return true
        }
        if (pickerKeyStr.isNotEmpty() && matchesParsedKey(event, parseKeyString(pickerKeyStr))) {
            commonKeyActionListener.listener.onKeyAction(
                KeyAction.ShowInputMethodPickerAction,
                KeyActionListener.Source.Keyboard,
            )
            return true
        }
        return false
    }

    private fun handleHardwareSymToggle(event: KeyEvent): Boolean {
        val hw = hardwareKeyboardPrefs
        val symKeyCombined = hw.symbolPickerKey.getValue()
        val isSymToggleKey = matchesParsedKey(event, parseKeyString(symKeyCombined))
        if (!isSymToggleKey) return false

        // Candidate total can be stale from previous sessions. Use visible UI state instead.
        val noActiveInput = preeditEmptyState.isEmpty &&
                (!kawaiiBar.isCandidateUiShowing() || horizontalCandidate.visibleCandidateCount() <= 0)
        if (!noActiveInput) return false

        toggleSymbolWindow()
        return true
    }

    /**
     * Sym（符号）键三态循环：自定义一行键盘 → 符号选择器 → 隐藏键盘（回主键盘）→ 再回到自定义 …
     * 循环顺序由 [AppPrefs.HardwareKeyboard.symFirst] 决定「首选」（自定义 or 符号）作为首选项，
     * 但三态必然依次经过，**不会**因首选而跳过某一态（修复：设符号优先后自定义键盘打不开的 bug）。
     * 键盘窗口高度随状态自动切换：符号选择器/主键盘为全高，自定义键盘为单行。
     */
    private enum class SymState { CUSTOM, SYMBOL, HIDDEN }

    /** 当前 Sym 状态；主键盘（NONE）或已隐藏均视为循环起点（null） */
    private fun currentSymState(): SymState? =
        when {
            keyboardWindow.isCustomKeyboardActive -> SymState.CUSTOM
            windowManager.isAttached(symbolPicker) -> SymState.SYMBOL
            else -> null
        }

    /** 按 [AppPrefs.HardwareKeyboard.symFirst] 排出循环顺序，首选项排在最前 */
    private fun symCycleOrder(): List<SymState> {
        val custom = SymState.CUSTOM
        val symbol = SymState.SYMBOL
        val hidden = SymState.HIDDEN
        return if (hardwareKeyboardPrefs.symFirst.getValue() == SymFirstTarget.CUSTOM)
            listOf(custom, symbol, hidden)
        else
            listOf(symbol, custom, hidden)
    }

    private fun applySymState(state: SymState) {
        when (state) {
            SymState.CUSTOM -> {
                // 先同步切到自定义布局（KeyboardWindow 的 view 已在启动时建好，见 createView=true），
                // 再 attach / 显示，保证 attach 出来的首帧就是单行，避免「先全高后单行」闪烁。
                keyboardWindow.switchLayoutSync(CustomKeyboard.Name)
                windowManager.setKeyboardWindowVisible(true)
                if (!windowManager.isAttached(keyboardWindow)) {
                    // attach 会自动 detach 当前窗口（如符号面板），无需手动摘
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
            SymState.SYMBOL -> {
                windowManager.setKeyboardWindowVisible(true)
                windowManager.attachWindow(PickerWindow.Key.Symbol)
                keyboardWindow.markSymbolPickerActive()
            }
            SymState.HIDDEN -> {
                // 隐藏键盘：收起键盘窗口（物理模式由 onLayoutSwitched 顺带收起 InputView）
                windowManager.setKeyboardWindowVisible(false)
                if (!windowManager.isAttached(keyboardWindow)) {
                    windowManager.attachWindow(KeyboardWindow)
                }
                keyboardWindow.switchLayout(TextKeyboard.Name)
                keyboardWindow.symMode = KeyboardWindow.SymMode.NONE
            }
        }
    }

    private fun toggleSymbolWindow() {
        val order = symCycleOrder()
        val current = currentSymState()
        val next = if (current == null) order.first()
        else order[(order.indexOf(current) + 1) % order.size]
        applySymState(next)
    }

    /**
     * Whether the IME is currently in PHYSICAL keyboard mode, synced from
     * [org.fcitx.fcitx5.android.input.InputDeviceManager.isVirtualKeyboard]. In physical mode this
     * [InputView] is held GONE (the virtual keyboard is hidden), so the symbol window — which
     * attaches onto the keyboard window — has no visible base unless we reveal it first.
     */
    internal var physicalKeyboardMode = false
        private set

    internal fun onKeyboardModeChanged(isVirtualKeyboard: Boolean) {
        physicalKeyboardMode = !isVirtualKeyboard
    }

    /**
     * Whether this [InputView] is currently revealed in physical-keyboard mode to host a window
     * that is a normal view inside the IME window (the symbol picker, or the number/letter
     * keyboard switched to from within the picker). Used by
     * [org.fcitx.fcitx5.android.input.FcitxInputMethodService.onComputeInsets] to decide the IME's
     * touchable region: such windows are subject to the IME's touchable insets (unlike the floating
     * CandidatesView which uses its own PopupWindow), so they must be made fully touchable while
     * revealed. Equivalent to `visibility == VISIBLE` in physical mode — InputView is hidden (GONE)
     * otherwise.
     */
    fun isInputViewRevealed(): Boolean = visibility == View.VISIBLE

    /**
     * Physical-keyboard symbol-key toggle used in PHYSICAL mode, where InputView no longer
     * receives live candidate/preedit events and its [handleHardwareSymToggle] "no active input"
     * guard is frozen (stale). This matches the configured symbol key
     * ([AppPrefs.HardwareKeyboard.symbolPickerKey], Alt_R on BlackBerry where SYM reports as
     * [KeyEvent.KEYCODE_ALT_RIGHT]) and toggles the window regardless of composition state. The
     * "no candidate shown" precondition is enforced by the caller
     * ([org.fcitx.fcitx5.android.input.FcitxInputMethodService]) against the live floating
     * CandidatesView state, not against InputView's frozen state.
     *
     * Because the symbol window attaches onto the (hidden) keyboard window in physical mode, we
     * reveal this [InputView] before opening it — i.e. show the virtual keyboard first, then the
     * symbol window / custom keyboard on top of it. The Sym key cycles three states — custom
     * keyboard → symbol picker → hidden (back to the main keyboard, which hides this [InputView]
     * again in physical mode via [keyboardWindow.onLayoutSwitched]) — so the third press hides the
     * keyboard.
     */
    fun handleHardwareSymKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val symKey = hardwareKeyboardPrefs.symbolPickerKey.getValue()
        if (!matchesParsedKey(event, parseKeyString(symKey))) return false

        val symbolPickerOpen = windowManager.isAttached(symbolPicker)
        val customActive = keyboardWindow.isCustomKeyboardActive
        if (symbolPickerOpen || customActive) {
            // 符号窗口 / 自定义键盘打开中 → 在两者间切换（不再回主键盘，见 toggleSymbolWindow）
            toggleSymbolWindow()
        } else {
            // Open the symbol window: in physical mode the keyboard window (its base) is hidden,
            // so reveal this InputView first.
            if (physicalKeyboardMode) {
                visibility = View.VISIBLE
                // The KawaiiBar candidate surface is a virtual-keyboard component and its event
                // collector is disabled (handleEvents == false) in physical mode, so it would
                // otherwise keep showing the last stale, frozen candidate list for the whole time
                // the InputView is revealed. Push it back to Idle (toolbar) — the floating window
                // remains the live candidate surface, and normal virtual-mode events will restore
                // it later.
                kawaiiBar.resetToIdleState()
            }
            toggleSymbolWindow()
        }
        // The symbol window lives inside this InputView, so revealing/hiding it changes how much
        // of the IME window must be touchable. Force the framework to recompute the touchable
        // insets (see [FcitxInputMethodService.onComputeInsets]).
        service.requestInsetsUpdate()
        return true
    }

    /**
     * 符号/表情/颜文字窗口（PickerWindow）打开时的物理键盘路由（BlackBerry SYM 面板）：
     * - 无任何 picker 窗口为当前窗口 → false（不消费，正常打字）。
     * - 命中 symbolPickerKey（SYM 键）→ false，交给 handleHardwareSymKey 关闭/切换窗口。
     * - 其余键一律吞掉（return true）：26 字母键按物理行映射网格位置选符号并上屏；
     *   auto-repeat（长按）吞掉防 spam；非字母键（数字/标点/Space/Enter/DEL/修饰键）吞掉防误触。
     */
    fun handleHardwarePickerSelection(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val picker = currentPickerWindow() ?: return false
        val hw = hardwareKeyboardPrefs
        val symKey = hw.symbolPickerKey.getValue()
        if (matchesParsedKey(event, parseKeyString(symKey))) return false
        // 翻页：复用候选分页快捷键（pageNextKey / pagePrevKey）。
        // 组合键（带 modifier）优先于同物理键的纯键绑定，
        // 例如 "Alt+grave"(上一页) 不被纯 "grave"(下一页) 抢走。
        val nextParsed = parseKeyString(hw.pageNextKey.getValue())
        val prevParsed = parseKeyString(hw.pagePrevKey.getValue())
        val nextMatches = matchesParsedKey(event, nextParsed)
        val prevMatches = matchesParsedKey(event, prevParsed)
        if (nextMatches || prevMatches) {
            val prevHasModifier = (prevParsed as? ParsedKey.Ref)?.key?.states != 0
            val nextHasModifier = (nextParsed as? ParsedKey.Ref)?.key?.states != 0
            val direction = when {
                prevMatches && prevHasModifier -> -1
                nextMatches && nextHasModifier -> 1
                prevMatches -> -1
                else -> 1
            }
            picker.page(direction)
            return true
        }
        // 仅 26 字母键消费（按位置选符号并吞掉，避免面板打开时误打字）；
        // 其余键（退格/数字/标点/空格/回车/修饰键等）一律放行，走原有按键路径。
        val pos = HardwarePickerLetterMap.positionOfKeyCode(event.keyCode) ?: return false
        if (event.repeatCount == 0) {
            picker.selectByLetter(pos.row, pos.col)
        }
        return true
    }

    /** 当前处于输入窗口的 picker（symbol / emoji / emoticon 三选一，windowManager 任意时刻至多一个）。 */
    private fun currentPickerWindow(): PickerWindow? =
        when {
            windowManager.isAttached(symbolPicker) -> symbolPicker
            windowManager.isAttached(emojiPicker) -> emojiPicker
            windowManager.isAttached(emoticonPicker) -> emoticonPicker
            else -> null
        }

    // 物理键 → 候选位置的映射已重构为数据驱动表，见上方 preciseShortcuts() / wideShortcuts() / resolveShortcutPosition()。

    private fun handleHardwareCandidatePaging(event: KeyEvent): Boolean {
        val hw = hardwareKeyboardPrefs
        val nextParsed = parseKeyString(hw.pageNextKey.getValue())
        val prevParsed = parseKeyString(hw.pagePrevKey.getValue())
        val nextMatches = matchesParsedKey(event, nextParsed)
        val prevMatches = matchesParsedKey(event, prevParsed)
        if (!nextMatches && !prevMatches) return false
        // A combo (modifier) binding takes precedence over a plain binding on the same physical key,
        // so e.g. "Alt+grave" (prev) is not stolen by a plain "grave" (next) binding.
        val prevHasModifier = (prevParsed as? ParsedKey.Ref)?.key?.states != 0
        val nextHasModifier = (nextParsed as? ParsedKey.Ref)?.key?.states != 0
        val direction = when {
            prevMatches && prevHasModifier -> -1
            nextMatches && nextHasModifier -> 1
            prevMatches -> -1
            else -> 1
        }
        horizontalCandidate.page(direction)
        return true
    }

    /**
     * When prediction (联想) candidates are showing — i.e. preedit is empty (user already
     * committed the previous word) but the candidate bar still has candidates — the first
     * Delete/Backspace press should clear those prediction candidates instead of deleting
     * the character before the cursor in the editor. The user explicitly asked for this
     * two-step behavior so they can dismiss an unwanted prediction without losing typed text.
     *
     * Returns true if the Delete key was consumed (prediction cleared); false to let the
     * key fall through to normal processing.
     */
    fun handleDeleteClearsPrediction(event: KeyEvent): Boolean {
        if (!shouldClearPredictionOnDelete(
                keyAction = event.action,
                keyCode = event.keyCode,
                repeatCount = event.repeatCount,
                isPreeditEmpty = preeditEmptyState.isEmpty,
                isCandidateUiShowing = kawaiiBar.isCandidateUiShowing(),
                candidateCount = horizontalCandidate.visibleCandidateCount(),
            )
        ) return false
        // Clear prediction candidates by resetting fcitx's input panel. This dismisses the
        // candidate list without committing anything; the editor's text is untouched.
        fcitx.launchOnReady { it.reset() }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        hardwareKeyboardPrefs.unregisterOnChangeListener(onHardwareKeyChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}

/**
 * Pure decision for [InputView.handleDeleteClearsPrediction]: should a Delete/Backspace press
 * clear the prediction candidates (instead of deleting the character before the cursor in the
 * editor)? The two-step behavior lets the user dismiss an unwanted prediction without losing
 * typed text.
 *
 * Extracted as a framework-free top-level function so the boundary can be covered by a plain JVM
 * unit test ([org.fcitx.fcitx5.android.DeleteClearsPredictionTest]) without constructing the
 * (heavy) Android View.
 *
 * @param keyAction [android.view.KeyEvent.getAction]
 * @param keyCode [android.view.KeyEvent.getKeyCode]
 * @param repeatCount [android.view.KeyEvent.getRepeatCount]
 */
internal fun shouldClearPredictionOnDelete(
    keyAction: Int,
    keyCode: Int,
    repeatCount: Int,
    isPreeditEmpty: Boolean,
    isCandidateUiShowing: Boolean,
    candidateCount: Int,
): Boolean {
    if (keyAction != KeyEvent.ACTION_DOWN) return false
    if (keyCode != KeyEvent.KEYCODE_DEL) return false
    if (repeatCount != 0) return false
    // Only intercept when there's no preedit (prediction mode) but candidates are visible.
    if (!isPreeditEmpty) return false
    if (!isCandidateUiShowing) return false
    if (candidateCount <= 0) return false
    return true
}
