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
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidateDisplayMode
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
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
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
        return if (windowManager.isKeyboardWindowVisible()) keyboardHeightPx else 0
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
    private var preciseShortcutsCache = mutableMapOf<Int, List<ShortcutRule>>()
    private var wideShortcutsCache: List<ShortcutRule>? = null

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
        wideShortcutsCache = null
    }

    private val hardwareKeyboardPrefs = AppPrefs.getInstance().hardwareKeyboard

    // Hold the ManagedPreference<String> directly (no `by` delegate) so we can call
    // .getValue() to branch on the active candidate display mode (巨硬 vs 普通) when picking
    // a candidate via candidate1Key (Space). With `by`, the property would be the unwrapped
    // String and the method would not resolve — see the same fix in HorizontalCandidateComponent.
    private val candidateDisplayModePref = AppPrefs.getInstance().hardwareKeyboard.candidateDisplayMode

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
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // keep the toolbar visible and collapse the button area by default
        windowManager.attachWindow(KeyboardWindow)

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

    // 1~5 候选的精细映射：以"居中候选"(Space) 为基准，左右物理键按相对偏移定位，
    // 因此候选数为 2/3/4 时空格两侧按钮（0 / SYM）也能选到对应候选。
    // candidate1(k1) 始终由 handleHardwareCandidateShortcut 处理为"居中选词"，不在此表内。
    // 候选数 > 5 走 wideShortcuts()；candidate 数正好 5 时本函数结果与 BB 槽位布局等价。
    private fun preciseShortcuts(count: Int): List<ShortcutRule>? {
        if (count <= 0 || count > 5) return null
        preciseShortcutsCache[count]?.let { return it }
        val hw = hardwareKeyboardPrefs
        val center = (count - 1) / 2
        val rules = mutableListOf<ShortcutRule>()
        // candidate2 (空格左侧 0 键)：居中左 1
        (center - 1).takeIf { it in 0 until count }
            ?.let { rules.add(ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), it)) }
        // candidate3 (空格右侧 SYM 键)：居中右 1
        (center + 1).takeIf { it in 0 until count }
            ?.let { rules.add(ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), it)) }
        // candidate4 (Shift_L)：居中左 2
        (center - 2).takeIf { it in 0 until count }
            ?.let { rules.add(ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), it)) }
        // candidate5 (Shift_R)：居中右 2
        (center + 2).takeIf { it in 0 until count }
            ?.let { rules.add(ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), it)) }
        preciseShortcutsCache[count] = rules
        return rules
    }

    // >5 候选（wide layout）：槽位号即可见位置，沿用原快捷键映射
    private fun wideShortcuts(): List<ShortcutRule> {
        wideShortcutsCache?.let { return it }
        val hw = hardwareKeyboardPrefs
        val rules = listOf(
            ShortcutRule(parseKeyString(hw.candidate2Key.getValue()), CandidateUi.BlackBerryLeftSlot),
            ShortcutRule(parseKeyString(hw.candidate3Key.getValue()), CandidateUi.BlackBerryInnerLeftSlot),
            ShortcutRule(parseKeyString(hw.candidate4Key.getValue()), CandidateUi.BlackBerryInnerRightSlot),
            ShortcutRule(parseKeyString(hw.candidate5Key.getValue()), CandidateUi.BlackBerryRightSlot),
        )
        wideShortcutsCache = rules
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
        val hw = hardwareKeyboardPrefs
        return matchesParsedKey(event, parseKeyString(hw.candidate1Key.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.candidate2Key.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.candidate3Key.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.candidate4Key.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.candidate5Key.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.symbolPickerKey.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.pageNextKey.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.pagePrevKey.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.toggleImeKey.getValue())) ||
            matchesParsedKey(event, parseKeyString(hw.pickerKey.getValue()))
    }

    fun handleHardwareCandidateShortcut(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // 全局动作（可配置快捷键）：切换输入法 / 显示输入法选择器。
        // 放在最前，确保无论候选窗是否显示都能触发。
        if (handleHardwareGlobalAction(event)) return true

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
                CandidateDisplayMode.fromStorage(candidateDisplayModePref.getValue())
            ) {
                CandidateDisplayMode.Macrohard -> (count - 1) / 2
                CandidateDisplayMode.Linear -> 0
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

        if (windowManager.isKeyboardWindowVisible() && windowManager.isAttached(symbolPicker)) {
            windowManager.setKeyboardWindowVisible(false)
            windowManager.attachWindow(KeyboardWindow)
        } else {
            windowManager.setKeyboardWindowVisible(true)
            windowManager.attachWindow(PickerWindow.Key.Symbol)
        }
        return true
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
