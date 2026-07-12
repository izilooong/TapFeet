/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
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
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
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

    fun handleHardwareCandidateShortcut(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if (event.keyCode == KeyEvent.KEYCODE_SPACE && event.isAltPressed) {
            service.postFcitxJob {
                toggleIme()
            }
             return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && event.isShiftPressed) {
            commonKeyActionListener.listener.onKeyAction(KeyAction.ShowInputMethodPickerAction, KeyActionListener.Source.Keyboard)
            return true
        }

        if (handleHardwareSymToggle(event)) return true

        if (!kawaiiBar.isCandidateUiShowing()) return false

        if (handleHardwareCandidatePaging(event)) return true

        val count = horizontalCandidate.visibleCandidateCount()
        if (count <= 0) return false

        // Space key: handle like other diamond keys (capture position, select via postFcitxJob)
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && !event.isAltPressed) {
            val targetPos = (count - 1) / 2  // active candidate position
            val activeIndex = horizontalCandidate.selectionIndexAtVisiblePosition(targetPos) ?: return false
            val vh = horizontalCandidate.view.findViewHolderForAdapterPosition(targetPos) as? CandidateViewHolder
            vh?.let {
                horizontalCandidate.prepareFlyAnimation(it.candidate.text, it.ui.text)
            }
            service.postFcitxJob {
                setCandidatePagingMode(horizontalCandidate.currentCandidatePagingMode())
                if (select(activeIndex)) return@postFcitxJob
                val candidate = horizontalCandidate.candidateAtVisiblePosition(targetPos) ?: return@postFcitxJob
                service.finishComposing()
                service.commitText(candidate.text)
            }
            return true
        }

        val target = shortcutTargetForKeyCode(event.keyCode, count) ?: return false

        val index = when (target) {
            is HardwareCandidateTarget.ByNumber -> horizontalCandidate.selectionIndexForLocalNumber(target.number)
            is HardwareCandidateTarget.ByVisiblePosition -> horizontalCandidate.selectionIndexAtVisiblePosition(target.position)
        } ?: return false

        // Capture position on UI thread before fcitx operation
        val displayIndexForAnimation = when (target) {
            is HardwareCandidateTarget.ByNumber -> horizontalCandidate.adapter.selectionIndexForDisplayNumber(target.number)
            is HardwareCandidateTarget.ByVisiblePosition -> target.position
        }
        if (displayIndexForAnimation != null) {
            val vh = horizontalCandidate.view.findViewHolderForAdapterPosition(displayIndexForAnimation) as? CandidateViewHolder
            vh?.let {
                horizontalCandidate.prepareFlyAnimation(it.candidate.text, it.ui.text)
            }
        }

        service.postFcitxJob {
            setCandidatePagingMode(horizontalCandidate.currentCandidatePagingMode())
            val candidate = when (target) {
                is HardwareCandidateTarget.ByNumber -> horizontalCandidate.candidateForLocalNumber(target.number)
                is HardwareCandidateTarget.ByVisiblePosition -> horizontalCandidate.candidateAtVisiblePosition(target.position)
            }
            if (select(index)) return@postFcitxJob
            if (candidate == null) return@postFcitxJob
            service.finishComposing()
            service.commitText(candidate.text)
        }
        return true
    }

    private fun handleHardwareSymToggle(event: KeyEvent): Boolean {
        val isSymToggleKey = when (event.keyCode) {
            KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_PICTSYMBOLS,
            KeyEvent.KEYCODE_ALT_RIGHT -> true
            else -> false
        }
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

    private fun shortcutTargetForKeyCode(keyCode: Int, candidateCount: Int): HardwareCandidateTarget? {
        // Keep explicit mapping for small candidate counts to match BlackBerry-style slots.
        when (candidateCount) {
            1 -> {
                return when (keyCode) {
                    KeyEvent.KEYCODE_SPACE -> HardwareCandidateTarget.ByNumber(1)
                    else -> null
                }
            }

            // display numbers: [1, 2]
            2 -> {
                return when (keyCode) {
                    KeyEvent.KEYCODE_SPACE -> HardwareCandidateTarget.ByNumber(1)
                    KeyEvent.KEYCODE_SYM,
                    KeyEvent.KEYCODE_PICTSYMBOLS,
                    KeyEvent.KEYCODE_ALT_RIGHT -> HardwareCandidateTarget.ByVisiblePosition(1)
                    else -> null
                }
            }

            // display numbers: [2, 1, 3]
            3 -> {
                return when (keyCode) {
                    KeyEvent.KEYCODE_SPACE -> HardwareCandidateTarget.ByNumber(1)
                    KeyEvent.KEYCODE_0,
                    KeyEvent.KEYCODE_NUMPAD_0 -> HardwareCandidateTarget.ByVisiblePosition(0)
                    KeyEvent.KEYCODE_SYM,
                    KeyEvent.KEYCODE_PICTSYMBOLS,
                    KeyEvent.KEYCODE_ALT_RIGHT -> HardwareCandidateTarget.ByVisiblePosition(2)
                    else -> null
                }
            }

            // display numbers: [2, 1, 3, 4]
            4 -> {
                return when (keyCode) {
                    KeyEvent.KEYCODE_SPACE -> HardwareCandidateTarget.ByNumber(1)
                    KeyEvent.KEYCODE_0,
                    KeyEvent.KEYCODE_NUMPAD_0 -> HardwareCandidateTarget.ByVisiblePosition(0)
                    KeyEvent.KEYCODE_SYM,
                    KeyEvent.KEYCODE_PICTSYMBOLS,
                    KeyEvent.KEYCODE_ALT_RIGHT -> HardwareCandidateTarget.ByVisiblePosition(2)
                    KeyEvent.KEYCODE_SHIFT_RIGHT -> HardwareCandidateTarget.ByVisiblePosition(3)
                    else -> null
                }
            }

            // display numbers: [4, 2, 1, 3, 5]
            CandidateUi.BlackBerryBottomRowKeyCount -> {
                return when (keyCode) {
                    KeyEvent.KEYCODE_SPACE -> HardwareCandidateTarget.ByNumber(1)
                    KeyEvent.KEYCODE_0,
                    KeyEvent.KEYCODE_NUMPAD_0 -> HardwareCandidateTarget.ByVisiblePosition(1)
                    KeyEvent.KEYCODE_SYM,
                    KeyEvent.KEYCODE_PICTSYMBOLS,
                    KeyEvent.KEYCODE_ALT_RIGHT -> HardwareCandidateTarget.ByVisiblePosition(3)
                    KeyEvent.KEYCODE_SHIFT_LEFT -> HardwareCandidateTarget.ByVisiblePosition(0)
                    KeyEvent.KEYCODE_SHIFT_RIGHT -> HardwareCandidateTarget.ByVisiblePosition(4)
                    else -> null
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            return HardwareCandidateTarget.ByNumber(1)
        }

        val slot = shortcutSlotForKeyCode(keyCode) ?: return null
        val position = shortcutVisiblePositionForSlot(candidateCount, slot) ?: return null
        return HardwareCandidateTarget.ByVisiblePosition(position)
    }

    private fun shortcutSlotForKeyCode(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> CandidateUi.BlackBerryLeftSlot
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> CandidateUi.BlackBerryInnerLeftSlot
            KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_PICTSYMBOLS,
            KeyEvent.KEYCODE_ALT_RIGHT -> CandidateUi.BlackBerryInnerRightSlot
            KeyEvent.KEYCODE_SHIFT_RIGHT -> CandidateUi.BlackBerryRightSlot
            else -> null
        }
    }

    private fun shortcutVisiblePositionForSlot(candidateCount: Int, slot: Int): Int? {
        // For wide layouts (more than 5 visible candidates), keep fixed slot-to-position mapping.
        return slot.takeIf { it in 0 until candidateCount }
    }

    private fun handleHardwareCandidatePaging(event: KeyEvent): Boolean {
        val keyChar = event.unicodeChar.takeIf { it > 0 }?.toChar()
        val isPageKey = when {
            event.keyCode == KeyEvent.KEYCODE_GRAVE -> true
            keyChar == '$' -> true
            keyChar == '`' -> true
            else -> false
        }
        if (!isPageKey) return false
        horizontalCandidate.page(if (event.isAltPressed) -1 else 1)
        return true
    }

    private sealed interface HardwareCandidateTarget {
        data class ByNumber(val number: Int) : HardwareCandidateTarget
        data class ByVisiblePosition(val position: Int) : HardwareCandidateTarget
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
