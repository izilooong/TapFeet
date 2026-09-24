/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.fcitx.fcitx5.android.input.swipe.KeyboardFlyTextSelector
import org.fcitx.fcitx5.android.input.swipe.SwipeDirection
import org.fcitx.fcitx5.android.input.swipe.cornerDeleteRegion
import org.fcitx.fcitx5.android.utils.DeviceInfo
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.HardwareChord
import org.fcitx.fcitx5.android.data.prefs.HardwareSpecialKeys
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.effects.CommitEffectsOverlay
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.normalizeKeyString
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import kotlin.math.max

class FcitxInputMethodService : LifecycleInputMethodService() {

    private lateinit var fcitx: FcitxConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val cachedKeyEvents = LruCache<Int, KeyEvent>(78)
    private var cachedKeyEventIndex = 0
    private val consumedHardwareCandidateShortcutKeys = HashSet<Int>()

    /**
     * Saves MetaState produced by hardware keyboard with "sticky" modifier keys, to clear them in order.
     * See also [InputConnection#clearMetaKeyStates(int)](https://developer.android.com/reference/android/view/inputmethod/InputConnection#clearMetaKeyStates(int))
     */
    private var lastMetaState: Int = 0

    private lateinit var pkgNameCache: PackageNameCache

    lateinit var decorView: View
        private set
    lateinit var contentView: FrameLayout
        private set
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null
    internal var effectsOverlay: CommitEffectsOverlay? = null

    private val navbarMgr = NavigationBarManager()
    private val inputDeviceMgr = InputDeviceManager { isVirtualKeyboard ->
        postFcitxJob {
            setCandidatePagingMode(if (isVirtualKeyboard) 0 else 1)
        }
        currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard)
        effectsOverlay?.setCandidatesView(candidatesView)
        if (isVirtualKeyboard) {
            hideStatusIcon()
        } else {
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        window.window?.let {
            navbarMgr.evaluate(it, isVirtualKeyboard)
        }
    }

    private var capabilityFlags = CapabilityFlags.DefaultFlags

    private val selection = CursorTracker()

    val currentInputSelection: CursorRange
        get() = selection.latest

    private val composing = CursorRange()
    private var composingText = FormattedText.Empty

    private fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    private var cursorUpdateIndex: Int = 0

    private var highlightColor: Int = 0x66008577 // material_deep_teal_500 with alpha 0.4

    private val prefs = AppPrefs.getInstance()
    private val inlineSuggestions by prefs.keyboard.inlineSuggestions
    private val ignoreSystemCursor by prefs.advanced.ignoreSystemCursor

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.advanced.disableAnimation,
        prefs.advanced.ignoreSystemWindowInsets,
    )

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, fcitx, theme)
        // Register with InputDeviceManager BEFORE handing the view to the framework: the manager
        // pushes handleEvents and starts the fcitx-event collector, neither of which needs the
        // view to be attached. This ordering guarantees that when attach fires (inside
        // setInputView below) the manager already points at THIS instance, so the attach-side
        // reconcile in BaseInputView.onAttachedToWindow cannot resurrect the outgoing instance.
        inputDeviceMgr.setInputView(newInputView)
        setInputView(newInputView)
        inputView = newInputView
        newInputView.onAltLatchChanged(hardwareKeyDispatch.altLatched)
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // Register with InputDeviceManager before adding to the window — same reasoning as in
        // [replaceInputView]: attach must find the manager already pointing at this instance.
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        candidatesView = newCandidatesView
        effectsOverlay?.setCandidatesView(newCandidatesView)
        return newCandidatesView
    }

    /**
     * Idempotent re-push of the current device mode onto InputView / CandidatesView.
     *
     * The attach-side counterpart to the detach that kills the fcitx-event collector
     * ([BaseInputView.onDetachedFromWindow] → `handleEvents = false`): when the mode itself never
     * changed, nothing else re-arms the view, and it stays deaf — preedit keeps flowing through
     * InputConnection while the candidate bar freezes. Called from attach, window-show and
     * input-session-start; safe to call any time.
     */
    internal fun reconcileInputViewEvents() {
        inputDeviceMgr.reapplyMode()
    }

    private fun replaceInputViews(theme: Theme) {
        navbarMgr.evaluate(window.window!!, inputDeviceMgr.isVirtualKeyboard)
        replaceInputView(theme)
        replaceCandidateView(theme)
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        replaceInputView(ThemeManager.activeTheme)
    }

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        replaceCandidateView(ThemeManager.activeTheme)
    }

    @Keep
    // Cache the inline suggestion request per theme: building the full InlineSuggestionUi
    // style (several Builders + Icon.setTint) on every system request is wasteful when
    // the theme hasn't changed.
    private var cachedInlineSuggestionTheme: Theme? = null
    private var cachedInlineSuggestionRequest: InlineSuggestionsRequest? = null

    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        cachedInlineSuggestionTheme = null
        cachedInlineSuggestionRequest = null
        replaceInputViews(it)
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit): Job {
        val job = fcitx.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        jobs.trySend(job)
        return job
    }

    override fun onCreate() {
        fcitx = FcitxDaemon.connect(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
        pkgNameCache = PackageNameCache(this)
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }
        super.onCreate()
        // Sync the system sound/haptic switches up-front: with a physical keyboard the first key
        // press can happen before the IME window is ever shown (which is the other sync point),
        // and the default "following system" feedback mode would otherwise stay silent.
        InputFeedbacks.syncSystemPrefs()
        // Decode the keypress samples now so the very first press already honours the configured
        // volume instead of falling back to the platform's fixed-level effect.
        InputFeedbacks.preloadSoundEffects()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        // Bolted onto the content view rather than the InputView: the latter is GONE in
        // hardware-keyboard mode and gets recreated on theme changes, this one is not.
        effectsOverlay = CommitEffectsOverlay(this).also {
            it.setCandidatesView(candidatesView)
            contentView.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        lastKnownConfig = resources.configuration
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.CommitStringEvent -> {
                commitText(event.data.text, event.data.cursor)
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    when (it.sym.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace -> handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return -> handleReturnKey()
                        FcitxKeyMapping.FcitxKey_Left -> handleArrowKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        FcitxKeyMapping.FcitxKey_Right -> handleArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        else -> if (it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    // use cached event if available
                    cachedKeyEvents.remove(it.timestamp)?.let { keyEvent ->
                        /**
                         * intercept the KeyEvent which would cause the default [android.text.method.QwertyKeyListener]
                         * to show a Gingerbread-style CharacterPickerDialog
                         */
                        if (keyEvent.unicodeChar == KeyCharacterMap.PICKER_DIALOG_INPUT.code) {
                            currentInputConnection?.sendKeyEvent(
                                KeyEvent(
                                    keyEvent.downTime, keyEvent.eventTime,
                                    keyEvent.action, keyEvent.keyCode,
                                    keyEvent.repeatCount, keyEvent.metaState, -1,
                                    keyEvent.scanCode, keyEvent.flags, keyEvent.source
                                )
                            )
                            return@event
                        }
                        currentInputConnection?.sendKeyEvent(keyEvent)
                        if (KeyEvent.isModifierKey(keyEvent.keyCode)) {
                            when (keyEvent.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    // save current metaState when modifier key down
                                    lastMetaState = keyEvent.metaState
                                }
                                KeyEvent.ACTION_UP -> {
                                    // only clear metaState that would be missing when this modifier key up
                                    currentInputConnection?.clearMetaKeyStates(lastMetaState xor keyEvent.metaState)
                                    lastMetaState = keyEvent.metaState
                                }
                            }
                        }
                        return@event
                    }
                    // simulate key event
                    val keyCode = it.sym.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        // recognized keyCode
                        val eventTime = SystemClock.uptimeMillis()
                        if (it.up) {
                            sendUpKeyEvent(eventTime, keyCode, it.states.metaState)
                        } else {
                            sendDownKeyEvent(eventTime, keyCode, it.states.metaState)
                        }
                    } else {
                        // no matching keyCode, commit character once on key down
                        if (!it.up && it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Fcitx KeyEvent: $it")
                        }
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                updateComposingText(event.data)
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                handleDeleteSurrounding(before, after)
            }
            is FcitxEvent.IMChangeEvent -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val im = event.data.uniqueName
                    val subtype = SubtypeManager.subtypeOf(im) ?: return
                    skipNextSubtypeChange = im
                    // [^1]: notify system that input method subtype has changed
                    switchInputMethod(InputMethodUtil.componentName, subtype)
                }
                if (inputDeviceMgr.evaluateOnInputMethodActivate()) {
                    showStatusIcon(StatusIconMapping.fromEntry(event.data))
                }
            }
            is FcitxEvent.SwitchInputMethodEvent -> {
                val (reason) = event.data
                if (reason != FcitxEvent.SwitchInputMethodEvent.Reason.CapabilityChanged &&
                    reason != FcitxEvent.SwitchInputMethodEvent.Reason.Other
                ) {
                    if (inputDeviceMgr.evaluateOnInputMethodSwitch()) {
                        // show inputView for [CandidatesView] when input method switched by user
                        forceShowSelf()
                    }
                }
            }
            is FcitxEvent.CandidateListEvent -> {
                lastCandidateListData = event.data
                // Candidate set changed → re-evaluate fly-text arming. On this device's default
                // config (show_candidates_window=Disabled) the engine emits THIS event and never
                // PagedCandidateEvent, so without a refresh here the fly-text gate would never
                // recompute and stay disarmed forever.
                refreshFlyTextState()
            }
            is FcitxEvent.PagedCandidateEvent -> {
                lastPagedCandidateData = event.data
                // Candidate set changed (appeared / cleared / repaged) → re-evaluate the fly-text
                // gate and re-map which candidate rects a gesture resolves against.
                refreshFlyTextState()
            }
            is FcitxEvent.InputPanelEvent -> {
                // When the floating candidate window isn't rendering the composing letters
                // (showPreedit == false, the default), push the preedit to the target text box so
                // the letters (e.g. pinyin) appear inline at the cursor instead of only inside our
                // own window. We reuse the same composing-text state machine as the (unused)
                // ClientPreeditEvent path, so commit/clear stays consistent.
                if (!prefs.candidates.showPreedit.getValue()) {
                    updateComposingText(event.data.preedit)
                }
            }
            else -> {}
        }
    }

    private fun handleDeleteSurrounding(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        if (before > 0) {
            selection.predictOffset(-before)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(before, after)
        } else {
            ic.deleteSurroundingText(before, after)
        }
    }

    private fun handleBackspaceKey() {
        val lastSelection = selection.latest
        if (lastSelection.isNotEmpty()) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            selection.predictOffset(-1)
        }
        // In practice nobody (apart form ourselves) would set `privateImeOptions` to our
        // `DeleteSurroundingFlag`, leading to a behavior of simulating backspace key pressing
        // in almost every EditText.
        if (currentInputEditorInfo.privateImeOptions != DeleteSurroundingFlag ||
            currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (lastSelection.isEmpty()) {
            if (lastSelection.start <= 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                currentInputConnection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                currentInputConnection.deleteSurroundingText(1, 0)
            }
        } else {
            currentInputConnection.commitText("", 0)
        }
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (actionLabel?.isNotEmpty() == true && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    private fun handleArrowKey(keyCode: Int) {
        val type = currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = currentInputEditorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (type == InputType.TYPE_NULL ||
            // confirm URL suggestion in browser location bar, see also https://bugzilla.mozilla.org/show_bug.cgi?id=1999915
            type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        ) {
            sendDownUpKeyEvents(keyCode)
            return
        }
        val (start, end) = currentInputSelection
        val offset = if (start == end) 1 else 0
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> start - offset
            KeyEvent.KEYCODE_DPAD_RIGHT -> end + offset
            else -> return
        }
        currentInputConnection.setSelection(target, target)
    }

    /**
     * Move the text cursor by one step in the swiped direction (fly-text cursor-move mode, active
     * only when there are no candidates). Left/right reuse [handleArrowKey] (which does a precise
     * `setSelection` for the collapsing caret); up/down have no layout-free equivalent, so they fall
     * back to a DPAD key event that the editor turns into a line move. A click confirms the flick
     * landed — the finger is on the keyboard surface, not the screen.
     *
     * Alt active (held, or double-tap latched) → extend the selection instead of moving the caret
     * ([InputView.flyExtendSelection], which reuses the selection-cluster machinery). Deliberately
     * scoped to THIS swipe path only: the Fn+S/F/E/D cursor/selection chords keep their own
     * bindings untouched.
     */
    private fun flyMoveCursor(dir: SwipeDirection) {
        playHardwareSound(InputFeedbacks.SoundEffect.Standard)
        // Alt active (held, or double-tap latched) → extend the selection instead of moving the
        // caret. Same "Alt is meant to be active" pair [withInjectedModifiers] trusts
        // ([physicalAltDown] / [altLatched]); [systemAltSticky] is ROM residue and deliberately
        // excluded. The latch is left untouched — latch, swipe-select as many times as needed,
        // then unlock with Alt/Space/Enter as usual.
        if ((physicalAltDown || hardwareKeyDispatch.altLatched) &&
            AppPrefs.getInstance().hardwareKeyboard.keyboardFlyTextAltSelect.getValue() &&
            inputView?.flyExtendSelection(dir) == true
        ) return
        val code = when (dir) {
            SwipeDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
            SwipeDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            SwipeDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            SwipeDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        if (dir == SwipeDirection.LEFT || dir == SwipeDirection.RIGHT) {
            handleArrowKey(code)
        } else {
            sendDownUpKeyEvents(code)
        }
    }

    fun commitText(text: String, cursor: Int = -1) {
        val ic = currentInputConnection ?: return
        inputView?.onCommitText(text)
        effectsOverlay?.onCommit(text)
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            ic.withBatchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    ic.setSelection(target, target)
                }
                ic.finishComposingText()
            }
            return
        }
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        if (cursor == -1) {
            selection.predict(start + text.length)
            ic.commitText(text, 1)
        } else {
            val target = start + cursor
            selection.predict(target)
            ic.withBatchEdit {
                commitText(text, 1)
                setSelection(target, target)
            }
        }
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun deleteSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        currentInputConnection?.commitText("", 1)
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        val lastSelection = selection.latest
        currentInputConnection?.also {
            val start = max(lastSelection.start + offsetStart, 0)
            val end = max(lastSelection.end + offsetEnd, 0)
            if (start > end) return
            selection.predict(start, end)
            it.setSelection(start, end)
        }
    }

    fun cancelSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        currentInputConnection?.setSelection(end, end)
    }

    private lateinit var lastKnownConfig: Configuration

    var lastCandidateListData = FcitxEvent.CandidateListEvent.Data()

    /**
     * Last [FcitxEvent.PagedCandidateEvent] seen by the service. Mirrors [lastCandidateListData]
     * but for the paged-mode event the floating [CandidatesView] relies on. Replayed by
     * [CandidatesView.onStartHandleFcitxEvent] so the floating window shows the live candidate
     * list immediately when it (re)starts collecting — the event flow has no replay, so a fresh
     * collector would otherwise miss the last event and render stale/empty candidates until the
     * engine emits again.
     */
    var lastPagedCandidateData = FcitxEvent.PagedCandidateEvent.Data.Empty

    override fun onConfigurationChanged(newConfig: Configuration) {
        /**
         * skip keyboard|keyboardHidden changes, because we have [inputDeviceMgr]
         * skip uiMode (system light/dark mode) changes, because we have [onThemeChangeListener]
         * to replace InputView(s) when needed
         * [android.inputmethodservice.InputMethodService.onConfigurationChanged] would call
         * resetStateForNewConfiguration() which calls initViews() causes InputView(s) to be replaced again
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1984
         */
        val f = ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        /**
         * Reset fcitx only when the change is NOT uiMode-only.
         * uiMode changes (system dark/light mode) are handled by
         * onThemeChangeListener which replaces InputViews. The fcitx
         * state (candidates, preedit) should be preserved.
         */
        if (diff and ActivityInfo.CONFIG_UI_MODE != diff) {
            postFcitxJob { reset() }
        }
        /**
         * perform `super.onConfigurationChanged` only when `newConfig` diff fall outside "skipped" flags
         * we have to calculate the mask ourselves because nobody knows how `handledConfigChanges` works
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1876
         */
        if (diff and f != diff) {
            super.onConfigurationChanged(newConfig)
            // `super` -> InputMethodService.onConfigurationChanged ->
            // resetStateForNewConfiguration() -> initViews() -> mWindow.setContentView(mRootView),
            // which wipes ALL children of android.R.id.content. The framework only redoes ITS
            // views; anything this service bolted on in onCreate is gone or stale — and every
            // captured reference (decorView, content children, dp densities) may be outdated.
            // Don't patch individual pieces (the overlay alone went through four rounds of
            // "spontaneously dead until restart" reports): re-run the whole init chain.
            reinitializeAfterConfigChange()
        }
        lastKnownConfig = newConfig
    }

    /**
     * Full re-initialization of everything this service attached to the IME window, after the
     * framework's `resetStateForNewConfiguration()` rebuilt its view hierarchy on a non-skipped
     * config change (screen size / density / locale / rotation — vendor "mini mode" resolution
     * switching included). One deterministic path instead of per-view band-aids, so no view can
     * survive a config change in a stale or detached state:
     *  1. window references (decorView / contentView) refreshed in case the window was recreated;
     *  2. InputView + CandidatesView fully rebuilt (KawaiiBar, InputDeviceManager sync, navbar);
     *  3. effects overlay recreated — its captured density/scaledDensity must match the new
     *     resolution — and re-bolted onto the fresh content view;
     *  4. the decor generic-motion channel reinstalled on the (possibly new) decorView.
     */
    private fun reinitializeAfterConfigChange() {
        // 1. Window references may themselves be stale if the window was recreated.
        window.window?.let { w ->
            decorView = w.decorView
            contentView = w.decorView.findViewById(android.R.id.content)
        }
        // 2. Keyboard + candidate views: full rebuild.
        replaceInputViews(ThemeManager.activeTheme)
        // 3. Effects overlay: recreate from scratch, then re-bolt above the fresh content.
        effectsOverlay?.release()
        (effectsOverlay as? View)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        effectsOverlay = CommitEffectsOverlay(this).also {
            it.setCandidatesView(candidatesView)
            contentView.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        // 4. The fly-text channel rides on the decorView; reinstall unconditionally
        //    (setOnGenericMotionListener replaces, so re-running is safe even if unchanged).
        decorMotionListenerInstalled = false
        installDecorMotionListener()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        try {
            highlightColor = styledColor(android.R.attr.colorAccent).alpha(0.4f)
        } catch (_: Exception) {
            Timber.w("Device does not support android.R.attr.colorAccent which it should have.")
        }
        InputFeedbacks.syncSystemPrefs()
        installDecorMotionListener()
        // A config change that fell outside the skip mask may have detached the overlay while
        // the window was hidden (reinitializeAfterConfigChange covers the change itself; this
        // catches any other detach path). Idempotent.
        ensureEffectsOverlayAttached()
        // Same "detached while hidden, then nobody re-armed it" class of bug applies to the fcitx
        // event channel: BaseInputView.onDetachedFromWindow cancels the collector job, and the
        // mode setter short-circuits on an unchanged value, so a re-attached InputView stayed deaf
        // — preedit kept updating while the candidate bar froze on its last page. Re-push the mode
        // unconditionally; idempotent.
        inputDeviceMgr.reapplyMode()
    }

    /**
     * Re-bolts the effects overlay onto [contentView] if anything detached it. Idempotent:
     * a no-op while the overlay is still attached.
     */
    private fun ensureEffectsOverlayAttached() {
        val overlay = effectsOverlay ?: return
        if (overlay.parent === contentView) return
        Timber.w(
            "effects: overlay detached from content view (parent=%s), re-attaching",
            overlay.parent
        )
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        overlay.setCandidatesView(candidatesView)
        contentView.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** Set once per process; [onWindowShown] may fire again for a re-shown window. */
    private var decorMotionListenerInstalled = false

    /**
     * The fly-text touch channel: the IME window's OWN generic-motion stream.
     *
     * Decompiled from this device's stock IME (`com.bigcui.ime`,
     * `MyInputMethodService.onWindowShown`): it grabs the window's decor view and installs
     * `setOnGenericMotionListener` + `setOnTouchListener`, both forwarding straight to
     * `ImeGestureManager.onMotionEvent`. No extra window, no band claim, no root / shizuku /
     * accessibility. Verified live twice over: 156/203 dispatcher samples landed on its
     * `InputMethod` window whose touchable region is only the bottom 78px, and our own identical
     * hook logged
     * `FlyText: decor motion hit source=0x100008 action=0 x=286.0 y=698.8 raw=286.0,698.8`
     * (0x100008 = SOURCE_TOUCHPAD; y inside the surface's declared 0..748.75 band; raw == display
     * because the decor sits at the display origin).
     *
     * So the CLASS_POSITION keyboard-surface stream reaches the IME window because that window has
     * a surface — NOT because its touchable region covers the band. The earlier "the IME has to
     * claim the band with a PopupWindow" design was wrong: that popup was a mask over the app's
     * screen for the whole time it was up, and the time-bounded-claim apparatus existed only to
     * bound that self-inflicted damage. Both are gone; this hook is the whole channel.
     *
     * Returning true consumes the event so it neither falls through to [onGenericMotionEvent]
     * (double feed) nor reaches the app window underneath, which would read a surface swipe as a
     * scroll. Only the surface's own source is consumed; every other device's motion falls through
     * untouched, per the "compare sources with `==`" rule (all pointer classes share the 0x2 bit).
     */
    private fun installDecorMotionListener() {
        if (decorMotionListenerInstalled || !::decorView.isInitialized) return
        decorMotionListenerInstalled = true
        decorView.setOnGenericMotionListener { _, event ->
            if (event.source != InputDevice.SOURCE_TOUCHPAD) {
                false
            } else {
                TouchProbeLog.record(TouchProbeLog.PATH_IME_MOTION, event)
                // Fed while armed (either gesture), and also while a gesture already in flight has
                // to see its own UP/CANCEL — a latched `gestureActive` would misread the next gesture.
                if (flyTextSelectorInitialized &&
                    (flyTextOn || flyTextCornerDeleteOn || flyTextSelector.gestureActive
                            || flyTextPickerPagingOn || flyTextCursorOn)
                ) {
                    flyTextSelector.onTouchEvent(event)
                }
                true
            }
        }
    }

    override fun onCreateInputView(): View? {
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // input method layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        // expand inputArea to fullscreen
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        /**
         * expand InputView to fullscreen, since [android.inputmethodservice.InputMethodService.setInputView]
         * would set InputView's height to [ViewGroup.LayoutParams.WRAP_CONTENT]
         */
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var cachedNavBarBg: View? = null

    /**
     * "Keyboard fly-text": in physical-keyboard mode, a swipe up the keyboard surface picks the
     * candidate whose on-screen column the finger is over, and a left/right swipe pages candidates.
     * Touch channel: [installDecorMotionListener] — the IME window's own generic-motion stream, the
     * same one the stock IME on this device uses. Created lazily because its lambda needs
     * [resources] (attach-time) and [fcitx].
     */
    private lateinit var flyTextSelector: KeyboardFlyTextSelector
    private var flyTextSelectorInitialized = false
    private var flyTextListenerRegistered = false
    private val flyTextListener =
        ManagedPreference.OnChangeListener<Boolean> { _, _ -> refreshFlyTextState() }

    /**
     * [SystemClock.elapsedRealtime] of the most recent hardware key event (down or up). Drives the
     * selector's typing guard: a surface contact while keys are being hit is a graze between
     * keystrokes, not a gesture. Recorded in [onKeyDown]/[onKeyUp] before any dispatch decision,
     * so keys consumed by shortcuts still count as typing activity.
     */
    private var lastHardwareKeyAt = 0L

    /**
     * Live state: fly-text is armed (pref on AND visible candidates, from either candidate event
     * source — this device's config emits [FcitxEvent.CandidateListEvent], not the paged variant).
     * Recomputed on every [refreshFlyTextState], read by [installDecorMotionListener] and by
     * [onGenericMotionEvent] to decide whether keyboard-surface motion belongs to the fly-text
     * gesture. Deliberately NOT time-bounded any more: the old window existed only because the
     * claiming popup ate the app's touches while it was up, and there is no popup left to bound.
     */
    private var flyTextOn = false
    /**
     * Corner-delete gesture armed: [AppPrefs.hardwareKeyboard.keyboardFlyText] + the corner-delete
     * sub-toggle, independent of candidate visibility (Backspace is valid even with no candidates,
     * unlike select/page). Read by the two motion channels to decide whether to feed the selector.
     */
    private var flyTextCornerDeleteOn = false
    /**
     * Master fly-text pref on (cached from [AppPrefs.hardwareKeyboard.keyboardFlyText]); feeds the
     * picker-paging arming gate so a keyboard-surface swipe can page an open symbol/emoji/emoticon
     * panel even when the candidate bar is hidden behind it.
     */
    private var flyTextPrefOn = false
    /**
     * True while the fly-text master pref is on AND a symbol/emoji/emoticon panel is the active
     * input window. The keyboard-surface swipe should then page that panel instead of the candidate
     * bar, and the motion channel must stay armed so the swipe reaches the selector even when the
     * candidate bar is hidden behind the panel.
     */
    private val flyTextPickerPagingOn: Boolean
        get() = flyTextPrefOn && inputView?.isPickerWindowOpen() == true
    /**
     * Cursor-move mode armed: the fly-text master pref is on AND there are no candidates on screen
     * AND no symbol/emoji/emoticon panel is open. The four-way keyboard-surface swipe then drives
     * the text caret (so a flick still does something useful with nothing to page/select). Suppressed
     * whenever a panel is open (that case pages the panel instead) or candidates are showing (that
     * case pages/selects).
     */
    private val flyTextCursorOn: Boolean
        get() = flyTextPrefOn &&
                AppPrefs.getInstance().hardwareKeyboard
                    .keyboardFlyTextCursorMove.getValue() &&
                !(lastPagedCandidateData.candidates.isNotEmpty() ||
                        lastCandidateListData.candidates.isNotEmpty()) &&
                inputView?.isPickerWindowOpen() != true
    /** Tracks the last logged [flyTextOn] value; arm/disarm transitions are logged once each. */
    private var lastFlyTextLogged = false

    /** Why fly-text is currently disarmed; carried into the DISARMED log line (diagnostics). */
    private var flyTextDisarmReason = "none"

    /**
     * True while a keyboard-surface gesture stream should be treated as a fly-text/diagnostic
     * gesture rather than a "show the soft keyboard" request: fly-text is armed, or the Lab page is
     * recording surface samples. While this holds, [onUpdateEditorToolType] suppresses the
     * virtual-keyboard flip so a surface gesture can't reconfigure the IME mid-swipe.
     *
     * Deliberately does NOT touch [onComputeInsets]: widening the touchable region to
     * [Insets.TOUCHABLE_INSETS_FRAME] makes the IME claim the WHOLE screen (app content reports a
     * ~full-screen IME inset and every touch outside it dies in the IME window) — an invisible
     * full-screen mask. The decor motion channel needs none of that.
     *
     * Computed, not cached: [TouchProbeLog.recording] is flipped by the Lab page's lifecycle and
     * nothing re-runs [refreshFlyTextState] on that flip, so a cached copy would go stale.
     */
    private val keyboardSurfaceProbing: Boolean
        get() = flyTextOn || TouchProbeLog.recording

    override fun onComputeInsets(outInsets: Insets) {
        // When a window is revealed inside this InputView in physical-keyboard mode (the symbol
        // picker, or the number/letter keyboard switched to from within it), the InputView is a
        // normal view inside the IME window — unlike the floating CandidatesView which uses its
        // own PopupWindow — so it must be touchable across its whole surface. Otherwise the items
        // above the navbar strip are untouchable. Reuse the virtual-keyboard touchable region.
        val revealedSurface = !inputDeviceMgr.isVirtualKeyboard &&
                inputView?.isInputViewRevealed() == true
        if (inputDeviceMgr.isVirtualKeyboard || revealedSurface) {
            // Keyboard is pinned to the bottom; its top is just window height minus its
            // height. The previous getLocationInWindow call was redundant (its result was
            // overwritten) and an extra IPC we can skip on this hot insets path.
            val top = inputView?.keyboardView?.let { kv ->
                decorView.height - kv.height.coerceAtLeast(0)
            } ?: 0
            outInsets.apply {
                contentTopInsets = top
                visibleTopInsets = top
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val navBar = cachedNavBarBg
                ?: decorView.findViewById<View>(android.R.id.navigationBarBackground)
                    .also { cachedNavBarBg = it }
            val n = navBar?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    /**
     * Re-apply the IME window layout so the framework re-invokes [onComputeInsets]. Needed when
     * the symbol window is revealed/hidden in physical-keyboard mode, because that changes how
     * much of the (otherwise hidden) [InputView] must be touchable.
     */
    internal fun requestInsetsUpdate() {
        window.window?.let { w ->
            val p = w.attributes
            w.setLayout(p.width, p.height)
        }
    }

    /**
     * Rects for keyboard fly-text hit-testing, each paired with the engine selection index.
     * Prefers the floating [CandidatesView]; on the config where the engine emits bulk candidate
     * lists (show_candidates_window=Disabled → only CandidateListEvent, no PagedCandidateEvent) the
     * floating window stays INVISIBLE and the visible surface is the InputView candidate bar, so
     * fall back to that. Both sources return `select`-ready indexes, so [onSelect] is a plain
     * `select(pos)` either way. One diagnostic line per empty probe keeps the active surface
     * observable in logcat.
     */
    private fun flyCandidateRects(): List<Pair<Int, Rect>> {
        val floating = candidatesView?.candidateScreenRects().orEmpty()
        if (floating.isNotEmpty()) return floating
        val bar = inputView?.flyCandidateRects().orEmpty()
        if (bar.isEmpty()) {
            Timber.i(
                "FlyText: no candidate rects (floatingVis=${candidatesView?.visibility}, " +
                        "floatingPaged=${lastPagedCandidateData.candidates.size}, " +
                        "barListed=${lastCandidateListData.candidates.size})"
            )
        }
        return bar
    }

    /**
     * Re-evaluate the keyboard-surface features (both physical-keyboard-only):
     *  1. Lab diagnostic capture — the decor motion channel records surface samples whenever
     *     [TouchProbeLog.recording] is on (the Lab page flips it in onResume/onPause), tagged
     *     [TouchProbeLog.PATH_IME_MOTION]. No switch, no window, no mask; on this hardware the
     *     samples arrive without any of that.
 *  2. Keyboard fly-text — [AppPrefs.hardwareKeyboard.keyboardFlyText] + visible candidates, from
 *     EITHER candidate event source (this device's config emits [FcitxEvent.CandidateListEvent],
 *     never PagedCandidateEvent). No "keyboard touch surface" hardware gate: the feature is offered
 *     on every device; hardware without a TOUCHPAD source simply receives no motion events.
     *
     * History, because it cost a lot to get wrong: fly-text used to run on a PopupWindow that
     * claimed the surface's display band, on the theory that the dispatcher routes the
     * CLASS_POSITION stream to whichever window owns the band. It does not — it delivers to the IME
     * window because that window has a surface (proved by decompiling the stock IME, by 5200
     * dispatcher samples landing on its 78px-tall window, and finally by our own
     * [installDecorMotionListener] logging hits on it). The popup was therefore pure overhead AND a
     * mask over the app's screen the whole time it was up — which is also why the claim had to be
     * time-bounded. All of it is gone; this function now only recomputes [flyTextOn] and makes sure
     * the channel is installed.
     *
     * Pref listener is registered lazily (kept as a field per ManagedPreference's "no anonymous
     * listeners" rule) so toggling the switch takes effect immediately, no re-focus needed.
     */
    private fun refreshFlyTextState() {
        val hw = AppPrefs.getInstance().hardwareKeyboard
        if (!flyTextListenerRegistered) {
            // One listener re-evaluates both fly-text arming inputs (the master pref AND the
            // corner-delete sub-toggle), so toggling either takes effect immediately, no re-focus.
            hw.keyboardFlyText.registerOnChangeListener(flyTextListener)
            hw.keyboardFlyTextCornerDelete.registerOnChangeListener(flyTextListener)
            flyTextListenerRegistered = true
        }
        // Neither gate uses !isVirtualKeyboard: on this device the candidates-window mode
        // (show_candidates_window) defaults to Disabled, whose evaluate* paths FORCE
        // isVirtualKeyboard=true while the user is in fact typing on the physical keyboard with
        // the soft keyboard hidden — so that flag is NOT a reliable "physical mode" indicator here.
        flyTextPrefOn = hw.keyboardFlyText.getValue()
        val hasCandidates = lastPagedCandidateData.candidates.isNotEmpty() ||
                lastCandidateListData.candidates.isNotEmpty()
        flyTextOn = flyTextPrefOn && hasCandidates
        // Corner-delete does not need candidates (Backspace always applies), so it arms on the two
        // prefs alone — but only while the parent fly-text pref is on (it is a sub-feature).
        flyTextCornerDeleteOn = flyTextPrefOn && hw.keyboardFlyTextCornerDelete.getValue()
        flyTextDisarmReason = when {
            !flyTextPrefOn -> "pref-off"
            !hasCandidates -> "no-candidates"
            else -> "none"
        }
        // Log the armed state on transitions only (not every keystroke), with the reason spelled out.
        if (flyTextOn != lastFlyTextLogged) {
            lastFlyTextLogged = flyTextOn
            if (flyTextOn) {
                Timber.i("FlyText: ARMED")
            } else {
                Timber.i("FlyText: DISARMED reason=$flyTextDisarmReason")
            }
        }

        // Lazily build the selector (needs resources + fcitx, available at runtime). It has to exist
        // before the first touch arrives, and the channel is installed independently of arming, so
        // both [flyTextSelectorInitialized] and the armed flags are checked at event time.
        if ((flyTextOn || flyTextCornerDeleteOn) && !flyTextSelectorInitialized) {
            flyTextSelector = KeyboardFlyTextSelector(
                density = resources.displayMetrics.density,
                candidateRectsProvider = { flyCandidateRects() },
                onSelect = { pos ->
                    // Sound first: the finger is on the keyboard surface, not the screen, so the
                    // click is the only immediate confirmation the up-swipe registered at all.
                    // A key-based pick (physical number key, bar tap) stays silent on purpose — the
                    // physical key already clicks and stacking a second click there was rejected.
                    playHardwareSound(InputFeedbacks.SoundEffect.Standard)
                    // Route through the bar's tap path so the fly animation fires like a normal
                    // pick; fall back to a plain engine select when the index isn't on the bar
                    // (stale rects, or the rects came from the floating CandidatesView).
                    if (inputView?.flySelectSelectionIndex(pos) != true) {
                        postFcitxJob { select(pos) }
                    }
                },
                onPage = { dir ->
                    // Clicks on EVERY recognised left/right swipe, including one that lands on the
                    // first/last page and therefore moves nothing: the swipe itself was understood,
                    // and silence there reads as "the gesture was ignored". Both directions share
                    // one sound — the page visibly moves, so the direction needs no audio cue.
                    // Same key click as a physical key press (SoundEffect.Standard), not a distinct
                    // paging tone — the swipe should feel like a hardware-keyboard action.
                    playHardwareSound(InputFeedbacks.SoundEffect.Standard)
                    // Honours the user's "swap page swipe direction" toggle, then pages the
                    // candidate bar locally for bulk lists (engine paging has nothing to move
                    // there); only falls back to engine paging for the floating window.
                    val d = if (AppPrefs.getInstance().hardwareKeyboard
                            .keyboardFlyTextSwapPage.getValue()
                    ) -dir else dir
                    // A symbol/emoji/emoticon panel is open → page it (same as the physical
                    // pageNext/pagePrev keys); skip the candidate bar so the two never fight.
                    if (inputView?.flyPagePicker(d) != true) {
                        if (inputView?.flyPageCandidates(d) != true) {
                            postFcitxJob { offsetCandidatePage(d) }
                        }
                    }
                },
                cornerRegionProvider = {
                    // The top-right corner of the keyboard surface, in display coordinates — the
                    // only zone a left swipe is read as Backspace from. Null when this device has no
                    // keyboard touch surface, in which case the selector's corner-delete is inert and
                    // a left swipe just pages.
                    DeviceInfo.keyboardSurfaceRect()?.let { cornerDeleteRegion(it) }
                },
                cornerDeleteEnabled = {
                    AppPrefs.getInstance().hardwareKeyboard.keyboardFlyTextCornerDelete.getValue()
                },
                onDelete = {
                    // Click feedback like a physical Backspace key: the finger is on the keyboard
                    // surface, not the screen, so the click is the only confirmation the delete fired.
                    playHardwareSound(InputFeedbacks.SoundEffect.Delete)
                    // Behave exactly like the physical Backspace key: the key goes through fcitx, which
                    // deletes the last composing character (pinyin preedit) FIRST and only falls back to
                    // deleting already-committed editor text when the preedit is empty. `handleBackspaceKey`
                    // instead sends KEYCODE_DEL straight to the editor and would skip the composing text
                    // entirely — so a flick from the corner deleted the wrong thing (committed text) while
                    // the candidate bar stayed put. Mirrors onKeyDown: clear 联想 prediction candidates on
                    // the first Delete when there is no preedit but candidates are showing.
                    val del = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                    if (inputView?.handleDeleteClearsPrediction(del) != true) {
                        forwardKeyEvent(del)
                        forwardKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                    }
                },
                typingGuard = {
                    SystemClock.elapsedRealtime() - lastHardwareKeyAt <
                        AppPrefs.getInstance().hardwareKeyboard.keyboardFlyTextGuardMs.getValue()
                },
                sensitivityProvider = {
                    AppPrefs.getInstance().hardwareKeyboard.keyboardFlyTextSensitivity.getValue()
                },
                cursorModeProvider = { flyTextCursorOn },
                onCursor = { flyMoveCursor(it) }
            )
            flyTextSelectorInitialized = true
        }

        // Make sure the touch channel is live. `decorView` is the service-level property (lateinit
        // View, assigned from the inner Window in onCreate); `isInitialized` is the real guard, not
        // `!= null` — the property is non-null by type, so a null check guards nothing, and reading
        // an un-assigned lateinit throws before the comparison ever runs. This path can fire from a
        // preference listener, which may beat onCreate's assignment, so the call is a no-op then and
        // [onWindowShown] retries.
        installDecorMotionListener()
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onEvaluateFullscreenMode() = false

    // 物理键盘按键「自带状态」子策略的持有者：Alt-latch 双击锁 + 长按键帽符号。
    private val hardwareKeyDispatch = HardwareKeyDispatch()

    /**
     * 框架/编辑器层 Alt sticky 状态（独立于应用层 [altLatched]）。
     *
     * 触发场景：长按 Alt 后 Android 框架的 InputConnection 会在 metaState 里残留
     * META_ALT_ON，应用层 `altLatched` 是 false，但所有后续按键事件都带 Alt meta。
     *
     * 检测方法：在 onKeyDown 处理非 Alt 键时，如果 [event.metaState] 含 [KeyEvent.META_ALT_ON]
     * 但 [physicalAltDown] 为 false，则说明系统处于 sticky 状态。
     */
    private var systemAltSticky = false

    /**
     * Physical modifier state tracked from the raw key-down/key-up stream.
     *
     * Why this is needed: when the Alt-latch logic consumes the Alt key's key-down (it returns
     * `true` for the latch key so a lone Alt never leaks into fcitx5), some Android builds stop
     * attaching `META_ALT_ON` to the *following* key event. As a result a combo like `Alt+grave`
     * arrives with no Alt meta and can never match. We therefore derive the authoritative modifier
     * state from the physical keys we actually see go down/up, and inject it into the effective
     * event used for matching/forwarding. `physicalAltDown` also covers the latched case.
     */
    private var physicalAltDown = false
    private var physicalCtrlDown = false
    private var physicalShiftDown = false

    private fun updatePhysicalModifiers(keyCode: Int, isDown: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> physicalAltDown = isDown
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> physicalCtrlDown = isDown
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> physicalShiftDown = isDown
        }
    }

    // ===== Long-press a physical key to input its keycap symbol (BlackBerry-style) =====
    // A held key (>= [HARDWARE_SYMBOL_LONG_PRESS_MS]) commits its [HardwareKeySymbolMap] symbol
    // instead of the normal character. A quick tap falls through to the normal key on key-up,
    // so existing typing is unchanged. See also [onKeyDown]/[onKeyUp].
    private val mainHandler = Handler(Looper.getMainLooper())

    // Long-press duration (ms) before a held key commits its keycap symbol. User-tunable via
    // [AppPrefs.HardwareKeyboard.longPressSymbolThreshold] (300–1000ms, default 400).
    private fun longPressSymbolThresholdMs(): Long =
        AppPrefs.getInstance().hardwareKeyboard.longPressSymbolThreshold.getValue().toLong()

    private fun longPressSymbolEnabled(): Boolean =
        AppPrefs.getInstance().hardwareKeyboard.longPressSymbolEnabled.getValue()

    // Length of the text immediately before the cursor INCLUDING the composing (preedit) region.
    // Used by the long-press-to-symbol retraction to measure how many characters a keystroke added to
    // the editor, by comparing this value captured at key-down with the value captured when the
    // long-press fires. The retraction then collapses any preedit into plain text and deletes exactly
    // that many characters via InputConnection — robust across every input method, because the count
    // covers BOTH the committed case (wubi / ziranma / plain text) and the preedit case (pinyin, or an
    // unfinished wubi code still in composing), so we no longer depend on guessing the IME's internal
    // state (which is what broke the previous boolean `composing.isEmpty()` and fcitx-DEL approaches).
    private fun textLengthBeforeCursor(): Int {
        val ic = currentInputConnection ?: return 0
        val before = ic.getTextBeforeCursor(1024, 0) ?: return 0
        return before.toString().codePointCount(0, before.length)
    }

    // Table-engine input methods (wubi / ziranma / cangjie / erbi / …) commit the pending preedit
    // when the engine is reset — TableEngine::reset runs commitBuffer(true) when
    // commitWhenDeactivate is enabled (its default) — so their preedit must be backspaced out
    // BEFORE reset(). Pinyin / English engines reset cleanly (PinyinEngine::doReset only clears
    // the panel), so they take the simple reset+commit path.
    private fun isTableIme(): Boolean =
        fcitx.runImmediately { inputMethodEntryCached.addon }
            .equals("Table", ignoreCase = true)


    // ===== Physical key press / keyboard-surface gesture sounds =====
    /**
     * Play the keyboard click sound for a physical key press, mirroring the on-screen keyboard
     * (which calls [InputFeedbacks.soundEffect] from `CustomGestureView` on ACTION_DOWN).
     *
     * The hardware-keyboard settings own the switch and the playback volume outright: only the sound
     * scheme (timbre) is shared with the virtual keyboard, and both are applied inside
     * [InputFeedbacks.soundEffectForHardwareKeyboard].
     */
    private fun playHardwareKeySound(keyCode: Int) {
        // Navigation / system keys are not typing keys and already have their own system feedback.
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER -> return
        }
        val effect = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> InputFeedbacks.SoundEffect.SpaceBar
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> InputFeedbacks.SoundEffect.Delete
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> InputFeedbacks.SoundEffect.Return
            else -> InputFeedbacks.SoundEffect.Standard
        }
        playHardwareSound(effect)
    }

    /**
     * The single funnel for every sound the HARDWARE keyboard makes: physical key presses
     * ([playHardwareKeySound]) and keyboard-surface gestures (up-swipe pick → [SoundEffect.Standard],
     * left/right paging swipes → [SoundEffect.Standard]).
     *
     * They share one pipeline — switch, volume and sound scheme — because to the user the surface
     * and the physical keys are the same "hardware keyboard". The gate itself lives in
     * [InputFeedbacks.soundEffectForHardwareKeyboard], so nothing is checked here.
     *
     * Key-based candidate selection deliberately does NOT come through here: the physical number key
     * already clicks, and stacking a second click on that path was rejected as noisy.
     */
    private fun playHardwareSound(effect: InputFeedbacks.SoundEffect) {
        InputFeedbacks.soundEffectForHardwareKeyboard(
            effect, AppPrefs.getInstance().hardwareKeyboard.keySoundVolume.getValue()
        )
    }

    fun isAltLatched(): Boolean = hardwareKeyDispatch.altLatched

    /** 框架/编辑器层是否处于 Alt sticky 状态（独立于应用层 latch）。 */
    fun isSystemAltSticky(): Boolean = systemAltSticky

    /** 应用层 latch 或框架层 sticky 任意一个为 true 都算 Alt 处于"锁定"展示态。 */
    fun isAltLockedOrSticky(): Boolean = hardwareKeyDispatch.altLatched || systemAltSticky

    /**
     * 手动覆盖 sticky 状态显示。系统层 sticky 无法可靠自动检测（不同 ROM 行为差异大），
     * 提供这个 API 给 UI / 设置 / 调试使用，强制设置锁图标显示。
     */
    fun setSystemAltStickyOverride(sticky: Boolean) {
        setSystemAltSticky(sticky)
        if (!sticky) {
            // 同时尝试清掉框架层 meta（如果存在）
            currentInputConnection?.clearMetaKeyStates(
                KeyEvent.META_ALT_ON or
                        KeyEvent.META_ALT_LEFT_ON or
                        KeyEvent.META_ALT_RIGHT_ON
            )
        }
    }

    /** Whether double-tap-left-Alt latching is enabled (setting: hardwareKeyboard.altLatchEnabled). */
    private fun altLatchEnabled(): Boolean =
        AppPrefs.getInstance().hardwareKeyboard.altLatchEnabled.getValue()

    /**
     * Whether Alt+Delete / Alt+Backspace deletes the whole line (setting:
     * hardwareKeyboard.altDeleteLineEnabled). When ON, edit keys keep their Alt meta so fcitx5
     * performs the kill-line; when OFF (default), the Alt meta is stripped and a single character
     * is deleted.
     */
    private fun altDeleteLineEnabled(): Boolean =
        AppPrefs.getInstance().hardwareKeyboard.altDeleteLineEnabled.getValue()

    fun toggleAltLatch() {
        setAltLatched(!hardwareKeyDispatch.altLatched)
    }

    /**
     * 强制解除 Alt 粘滞（应用层 [altLatched] + 框架/编辑器层 sticky meta）。
     *
     * 用于：
     * - 长按 Alt 后系统底层 sticky 被触发，单纯按 Alt 键无法解除的场景
     * - 外部调用方（如 UI 按钮、设置项）希望无条件清掉 Alt sticky 状态
     */
    fun unlockAltLatch() {
        clearAltLatchAndMetaState()
    }

    private fun setAltLatched(locked: Boolean) {
        if (hardwareKeyDispatch.altLatched == locked) return
        hardwareKeyDispatch.altLatched = locked
        inputView?.onAltLatchChanged(locked)
    }

    private fun setSystemAltSticky(sticky: Boolean) {
        if (systemAltSticky == sticky) return
        systemAltSticky = sticky
        inputView?.onSystemAltStickyChanged(sticky)
    }

    /**
     * 仅清掉系统/编辑器层 sticky meta + 本地 flag，不动应用层 [altLatched]。
     * 用于 first-tap 分支消费 Alt 键、already-latched 分支等场景。
     */
    private fun clearSystemAltSticky() {
        setSystemAltSticky(false)
        currentInputConnection?.clearMetaKeyStates(
            KeyEvent.META_ALT_ON or
                    KeyEvent.META_ALT_LEFT_ON or
                    KeyEvent.META_ALT_RIGHT_ON
        )
    }

    /**
     * 同时清掉应用层 [altLatched] 和 Android 框架层在 [InputConnection] 上维护的
     * Alt latched/locked meta 状态。InputConnection.clearMetaKeyStates 只会清掉
     * sticky / latched / locked 状态，不会影响当前正在按住的物理修饰键。
     */
    private fun clearAltLatchAndMetaState() {
        setAltLatched(false)
        clearSystemAltSticky()
        hardwareKeyDispatch.lastAltTapEventTime = 0L
    }

    private fun isAnyAltKeyCode(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
    }

    /**
     * Whether [event] is the configured Alt-latch trigger key
     * (setting: hardwareKeyboard.altLatchKey, default "Alt_L").
     *
     * The latch key is a bare physical key press (typically a modifier like Alt_L), so it is matched
     * by the keysym derived from the event's keyCode. [HardwareSpecialKeys] names (e.g. "Sym")
     * resolve to pseudo keys that have no keysym. An empty configured value disables latching
     * entirely.
     */
    // Self-invalidating memo of the parsed alt-latch key. Reparsing only happens when the
    // configured string actually changes (rare), instead of on every physical key press.
    private var cachedAltLatchString: String? = null
    private var cachedAltLatchKey: Key? = null

    private fun isAltLatchKey(event: KeyEvent): Boolean {
        val keyString = AppPrefs.getInstance().hardwareKeyboard.altLatchKey.getValue()
        if (keyString.isEmpty()) return false
        HardwareSpecialKeys.entryForName(keyString)?.let { return it.matches(event.keyCode) }
        if (keyString != cachedAltLatchString) {
            cachedAltLatchString = keyString
            cachedAltLatchKey = Key.parse(normalizeKeyString(keyString))
        }
        val key = cachedAltLatchKey ?: return false
        if (key.sym == 0) return false
        val symFromKeyCode = FcitxKeyMapping.keyCodeToSym(event.keyCode)
        return symFromKeyCode == key.sym ||
                (event.unicodeChar != 0 && event.unicodeChar == key.sym)
    }

    private fun isAltUnlockKeyCode(keyCode: Int): Boolean {
        return isAnyAltKeyCode(keyCode) ||
                keyCode == KeyEvent.KEYCODE_SPACE ||
                keyCode == KeyEvent.KEYCODE_ENTER
    }

    /**
     * Rebuild [event] with the authoritative modifier meta-state: our tracked physical modifier
     * state (see [physicalAltDown] etc.) plus any Alt added by latching. This guarantees combos
     * such as `Alt+grave` carry the Alt meta even when the OS failed to attach it after the latch
     * key's key-down was consumed.
     *
     * Edit keys (Backspace / Delete) are intentionally excluded from the Alt injection so that
     * `Alt+Delete` does not get rewritten to "delete whole line" by the fcitx5 side. Users
     * expect Delete (with or without Alt held) to delete a single character.
     */
    private fun withInjectedModifiers(event: KeyEvent): KeyEvent {
        var meta = event.metaState
        val isEditKey = event.keyCode == KeyEvent.KEYCODE_DEL ||
                event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL
        if (isEditKey && !altDeleteLineEnabled()) {
            // By default, edit keys must never carry Alt meta, even if the OS attached it because
            // physical Alt is held. Otherwise Alt+Backspace / Alt+Delete is forwarded to fcitx5
            // with Alt meta, and the fcitx5 side maps Alt+Delete to "delete whole line" (X11
            // kill-line), which is not what mobile IME users expect. When the setting is ON we
            // intentionally keep Alt (the else-branch below re-injects it from our tracked
            // physical/latched Alt state) so fcitx5 performs the kill-line instead.
            meta = meta and (KeyEvent.META_ALT_ON or
                    KeyEvent.META_ALT_LEFT_ON or
                    KeyEvent.META_ALT_RIGHT_ON).inv()
        } else if (physicalAltDown || hardwareKeyDispatch.altLatched) {
            // Alt is genuinely intended (physically held, or app-level latched via double-tap
            // Alt): (re)inject it so combos like Alt+grave carry Alt even when the OS failed to.
            meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        } else {
            // The framework may report META_ALT_ON on its own (e.g. Q25 locks Alt after a
            // long-press of the Alt key, and some ROMs keep a sticky Alt across the next key).
            // That is NOT a deliberate Alt+key combo, so strip it — otherwise a long-pressed Alt
            // would leak Alt onto every following key (Alt+letter, Alt+number...) and re-trigger a
            // lock. Only physicalAltDown / altLatched count as "Alt is meant to be active".
            meta = meta and (KeyEvent.META_ALT_ON or
                    KeyEvent.META_ALT_LEFT_ON or
                    KeyEvent.META_ALT_RIGHT_ON).inv()
        }
        if (physicalCtrlDown) {
            meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (physicalShiftDown) {
            meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (meta == event.metaState) return event
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            event.keyCode,
            event.repeatCount,
            meta,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source
        )
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        // reason to use a self increment index rather than timestamp:
        // KeyUp and KeyDown events actually can happen on the same time
        val timestamp = cachedKeyEventIndex++
        cachedKeyEvents.put(timestamp, event)
        val sym = KeySym.fromKeyEvent(event)
        if (sym != null) {
            val states = KeyStates.fromKeyEvent(event)
            val up = event.action == KeyEvent.ACTION_UP
            postFcitxJob {
                sendKey(sym, states, event.scanCode, up, timestamp)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    /**
     * 丢弃某个 keyCode 上待定的「长按键帽符号」。
     *
     * 那个 pending 是在 [onKeyDown] 里**登记得比消费判断更早**的（长按分支刻意 fall through，
     * 让字母先正常上屏）。因此任何消费掉 DOWN 事件的快捷键，如果不主动取消它，用户按住这个键
     * 超过阈值时定时器仍会去替换正文、把键帽符号打出来 —— 表现为「绑了快捷键的字母键，长按会冒符号」。
     */

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Typing guard clock: any hardware key (consumed or not) marks the surface stream as
        // "typing in progress" for the window configured by hw.keyboardFlyTextGuardMs — grazes
        // between keystrokes must not arm fly-text gestures.
        lastHardwareKeyAt = SystemClock.elapsedRealtime()

        // Lab-page probe: record every key BEFORE any dispatch decision, so the log also covers keys
        // a shortcut ends up consuming — and, by their absence, proves which keys never get
        // dispatched to the IME window at all. No-op unless the Lab page turned recording on.
        KeyProbeLog.record(event)

        // Nothing is armed from here any more: the decor motion channel is live for the whole IME
        // window lifetime, and [flyTextOn] only means "pref on + candidates visible", re-evaluated
        // when either changes. (The old code opened a time-bounded claim window per fresh keystroke,
        // auto-repeat skipped, because that window was the only way to see the surface at all.)

        // When the target editor requests key capture (e.g. KeyCaptureUi/KeyPreferenceUi),
        // do not consume physical key events so they reach the EditText's OnKeyListener.
        if (currentInputEditorInfo.privateImeOptions?.contains(KeyCaptureFlag) == true) {
            return false
        }

        // 伪修饰键（Fn / Sym）的按住状态 + tap-hold 挂起消解。刻意放在捕获页早退**之后**：
        // 捕获页里的按键是给对话框录键位用的，不该污染「Fn 是否按着」这种运行期状态。
        // 也必须早于下面所有派发分支，动作快捷键（`handleHardwareActionShortcut`）要读它。
        HardwareChord.onKeyDown(keyCode)

        // Key sound on press, exactly like the on-screen keyboard. Done before any dispatch logic
        // so every physical key clicks regardless of which branch ends up consuming it. Auto-repeat
        // is skipped so holding a key doesn't machine-gun the sound (the virtual keyboard's repeat
        // handler doesn't replay it either).
        if (event.repeatCount == 0) {
            playHardwareKeySound(keyCode)
        }

        // ========== Earliest metaState probe (deprecated) ==========
        // We used to flag a framework-level Alt sticky/lock here (set systemAltSticky) so a
        // long-pressed Alt wouldn't leak Alt onto following keys. That is now handled
        // authoritatively in [withInjectedModifiers]: any META_ALT_ON the framework attaches on
        // its own is stripped unless Alt is physically held (physicalAltDown) or app-latched
        // (altLatched). No bookkeeping needed here.

        // Snapshot physicalAltDown BEFORE updatePhysicalModifiers mutates it, so we can tell a
        // *genuine* new Alt press apart from a *spurious duplicate* Alt down. Some ROMs/firmwares
        // (notably Q25) fire a second KEYCODE_ALT down with repeatCount==0 during a long-press of
        // Alt — that synthetic down is what was flipping the app-level Alt latch (KawaiiBar lock
        // button) on a long-press. A genuine press always arrives after an up, so physicalAltDown
        // is false at that moment; a duplicate during a hold has it true.
        val wasAltDown = physicalAltDown

        // Track physical modifier state from the raw stream (authoritative for combo matching).
        updatePhysicalModifiers(keyCode, true)

        // Swallow a redundant Alt down that arrives while Alt is already physically held
        // (e.g. the Q25 firmware's synthetic second KEYCODE_ALT down during a long-press, or
        // Alt auto-repeat). The genuine down was already forwarded; re-forwarding it would
        // mismatched an up and can leave fcitx5 thinking Alt is still down. A genuine double-tap
        // is never swallowed here because its second press arrives after an up (wasAltDown=false).
        if (isAnyAltKeyCode(keyCode) && wasAltDown) return true

        // Swallow auto-repeat of a key whose long-press-to-symbol is pending, so holding it doesn't
        // spam the underlying character. Skip in non-text apps (games/emulators): they hold keys for
        // movement/action and must keep receiving every repeat — the long-press feature isn't active
        // there anyway, so swallowing repeats would just make held keys dead.
        if (event.repeatCount > 0 && !inputDeviceMgr.isNullInputType() &&
            hardwareKeyDispatch.isLongPressPending(keyCode)
        ) {
            return true
        }

        // System-level Alt sticky detection (deprecated):
        // We no longer flag a framework Alt-lock here. Whether the framework has Alt "stuck"
        // is irrelevant now — [withInjectedModifiers] strips any META_ALT_ON the OS attaches on
        // its own (long-press Alt, sticky ROM behavior) unless Alt is physically held or
        // app-latched, so a locked Alt can no longer leak onto following keys or re-trigger a
        // lock. The Alt-lock button is now driven solely by the app-level [altLatched] (double-tap
        // Alt), which is genuine user intent.

        // Alt-latch 双击锁状态机 → HardwareKeyDispatch（持有 altLatched / lastAltTapEventTime /
        // altLatchConsumedThisGesture / altDownStartTime 等内部状态）。命中消费返回 true，否则继续下行。
        hardwareKeyDispatch.dispatchAltLatchDown(keyCode, event, wasAltDown)?.let { return it }

        // ===== 符号窗口打开时：物理键盘直接选符号（BlackBerry SYM 面板） =====
        // 必须在下方长按键帽符号检测之前拦截：符号窗口打开时按字母键应选符号，
        // 不能打字 / 触发长按替换。SYM 键除外——留给下方的候选/符号键分发处理。
        // 本分支消费的键经 consumedHardwareCandidateShortcutKeys 在 onKeyUp 一并吞掉。
        if (inputView?.handleHardwarePickerSelection(event) == true) {
            consumedHardwareCandidateShortcutKeys.add(keyCode)
            return true
        }

        // 长按键帽符号（BlackBerry 风格）→ HardwareKeyDispatch 登记 pending，总是 fall-through
        // 让按键的普通字符照常上屏，到阈值仍按住才由 dispatch 内的定时器替换。
        hardwareKeyDispatch.armLongPressSymbol(keyCode, event)

        val isEditKey = event.keyCode == KeyEvent.KEYCODE_DEL ||
                event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL
        // Belt-and-suspenders: when system Alt sticky is flagged, synchronously clear
        // the editor's metaState right before sending the edit key. This catches the rare
        // case where a previous Alt key-down/up race leaves the InputConnection holding
        // META_ALT_ON even after withInjectedModifiers stripped it from the event we
        // forward. Without this, fcitx5 may still see Alt+Backspace and run a line-kill
        // shortcut on some ROMs.
        if (isEditKey && systemAltSticky && !altDeleteLineEnabled()) {
            currentInputConnection?.clearMetaKeyStates(
                KeyEvent.META_ALT_ON or
                        KeyEvent.META_ALT_LEFT_ON or
                        KeyEvent.META_ALT_RIGHT_ON
            )
        }

        val effectiveEvent = withInjectedModifiers(event)

        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceMgr.evaluateOnKeyDown(effectiveEvent, this)) {
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        if (event.repeatCount == 0) {
            // 动作快捷键（开关类 + 文本编辑类）第一优先。刻意放在候选 / 符号 / 翻页判断之前，并且放在
            // `inputDeviceMgr.isVirtualKeyboard` 分支**之外**：
            //  - 开关类动作与候选窗状态、preedit、编辑器焦点全无关系，若挂在后面的 early-return
            //    之下，就会变成"没在打字时按不动"的静默失效（hideStatusBar、选字特效都栽过这类坑）；
            //  - 物理键盘模式下 InputView 已不是候选面，动作键仍必须可达。
            // 消费后记入 consumedHardwareCandidateShortcutKeys，交给 onKeyUp 一并吞掉。
            if (inputView?.handleHardwareActionShortcut(effectiveEvent) == true) {
                hardwareKeyDispatch.cancelLongPressSymbol(keyCode)
                consumedHardwareCandidateShortcutKeys.add(keyCode)
                return true
            }
            // Candidate-selection dispatch. The two surfaces handle different key sets:
            //  - Virtual keyboard mode: the horizontal candidate bar (InputView) is the surface.
            //  - Physical keyboard mode: the floating CandidatesView is the surface.
            // In PHYSICAL mode we probe CandidatesView FIRST, then fall back to InputView. This
            // matters because a candidate key can also be bound to the symbol picker (the
            // blackberry profile binds Alt_R to BOTH candidate3 and symbolPicker). InputView's
            // symbol toggle would otherwise swallow the key before the floating window ever sees
            // it — leaving the 3rd candidate unselectable — because in physical mode InputView no
            // longer receives candidate events, so its "no active input" guard is permanently
            // true and the toggle always fires. Probing CandidatesView first lets the candidate
            // win when candidates are showing, and the symbol picker still reaches InputView when
            // they are not. Global actions keep falling through to InputView as before.
            // In VIRTUAL mode only InputView is consulted (CandidatesView isn't the surface).
            val handled = if (!inputDeviceMgr.isVirtualKeyboard) {
                // 和弦修饰键兼符号键的按下必须**先于候选面**挂起：Elite 的 Fn 同时也是
                // candidate3Key（Q25 的 Alt_R 同理），让候选面先看到这次按下的话，「按住 Fn + 字母」
                // 在打字途中会先把第 3 个候选选掉 —— 匹配逻辑再对也只会打成错字。
                // 挂起后松手时补发（见 onKeyUp 的 tap-hold 收尾），两个角色都不丢。
                inputView?.handleHardwareChordTapHold(effectiveEvent) == true ||
                    // Floating CandidatesView is the primary surface in physical mode.
                    candidatesView?.handleHardwareCandidateShortcut(effectiveEvent) == true ||
                    // When the floating window isn't showing candidates, the symbol key (Alt_R on
                    // BlackBerry, where SYM reports as KEYCODE_ALT_RIGHT) opens the symbol window
                    // directly. InputView's own noActiveInput guard is frozen in physical mode, so
                    // we gate on the live floating-window state and toggle via the unguarded path.
                    (candidatesView?.isShowingCandidates() != true &&
                        inputView?.handleHardwareSymKey(effectiveEvent) == true) ||
                    // Fall through to InputView for global actions and everything else.
                    inputView?.handleHardwareCandidateShortcut(effectiveEvent) == true
            } else {
                inputView?.handleHardwareCandidateShortcut(effectiveEvent) == true
            }
            if (handled) {
                // 同上：被候选 / 符号 / 翻页快捷键消费掉的键也不能再变成键帽符号
                // （把候选键绑到字母键时，长按同样会冒符号 —— 同一个洞，两处一起堵）。
                hardwareKeyDispatch.cancelLongPressSymbol(keyCode)
                consumedHardwareCandidateShortcutKeys.add(keyCode)
                return true
            }
        }
        // Prediction-candidate dismiss: when 联想 candidates are showing (no preedit), the first
        // Delete press clears them instead of deleting editor text. See InputView for full logic.
        if (event.repeatCount == 0 && inputView?.handleDeleteClearsPrediction(effectiveEvent) == true) {
            return true
        }
        return forwardKeyEvent(effectiveEvent) || super.onKeyDown(keyCode, effectiveEvent)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        lastHardwareKeyAt = SystemClock.elapsedRealtime()
        KeyProbeLog.record(event)

        if (currentInputEditorInfo.privateImeOptions?.contains(KeyCaptureFlag) == true) {
            return false
        }

        // Track physical modifier state from the raw stream (authoritative for combo matching).
        updatePhysicalModifiers(keyCode, false)

        // 伪修饰键抬起：清掉按住状态。放在最前面 —— 下面几条路径都可能提前 return
        // （长按符号 fired、Alt 锁定消费），漏清一次就是把「Fn 按着」永久留在那儿，
        // 之后每个字母都会被判成和弦（按 E 就切特效），比单纯不生效危险得多。
        HardwareChord.onKeyUp(keyCode)

        // 长按符号 pending 解析 → HardwareKeyDispatch（已长按 fired 则吞掉 up，否则 fall through）。
        if (hardwareKeyDispatch.resolveLongPressSymbolUp(keyCode)) return true

        // Long-press Alt → clear the framework's native sticky/locked Alt meta.
        // On the Q25 / some ROMs, holding Alt past a threshold makes the framework enter a native
        // Alt-lock that it keeps across the next key. We do NOT surface that as a sticky-lock state
        // anymore (the Alt-lock button is now driven only by the app-level double-tap latch). We
        // just proactively clear the framework's sticky meta on a long-press Alt release so the
        // following key starts clean. [withInjectedModifiers] is the real guarantee that a framework
        // Alt-lock can't leak onto following keys (it strips META_ALT_ON unless Alt is physically
        // held or app-latched), so this clear is belt-and-suspenders.
        if (isAnyAltKeyCode(keyCode) && event.repeatCount == 0) {
            val duration = event.eventTime - hardwareKeyDispatch.altDownStartTime
            if (duration >= hardwareKeyDispatch.altLongPressThresholdMs) {
                currentInputConnection?.clearMetaKeyStates(
                    KeyEvent.META_ALT_ON or
                            KeyEvent.META_ALT_LEFT_ON or
                            KeyEvent.META_ALT_RIGHT_ON
                )
                Timber.d("Long-press Alt released (${duration}ms): cleared framework sticky Alt meta")
            }
        }

        // Alt-latch key-up 处理 → HardwareKeyDispatch（命中消费返回 true/false 结束 onKeyUp，
        // 非 latch 键返回 null 让主函数继续）。
        hardwareKeyDispatch.dispatchAltLatchUp(keyCode, event)?.let { return it }
        // tap-hold 收尾：符号键按下时被 [HardwareChord.armSymbolTap] 挂起（物理模式的调用点在
        // onKeyDown 的派发链最前，虚拟模式在 InputView 的两个符号键入口），若这次手势没被任何和弦
        // 用掉，就在这里补上「轻按」那一下。**必须放在下面 consumedHardwareCandidateShortcutKeys
        // 之前**：挂起那次按下是被消费掉的（已记进那个集合），放在它之后的话 up 会先被吞掉，
        // 轻按就永远补不发出来。
        //
        // 补发顺序 = 这个键在按下时本该走的顺序：先当候选键（Elite 的 Fn 兼 candidate3），
        // 候选面不接（没显示候选 / 候选数不够）才切符号窗口。
        if (HardwareChord.consumeSymbolTap(keyCode)) {
            if (candidatesView?.handleChordTapRelease(event) != true) {
                inputView?.onHardwareSymbolTapReleased()
            }
            return true
        }
        if (consumedHardwareCandidateShortcutKeys.remove(keyCode)) {
            return true
        }
        val isEditKey = event.keyCode == KeyEvent.KEYCODE_DEL ||
                event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL
        if (isEditKey && systemAltSticky && !altDeleteLineEnabled()) {
            currentInputConnection?.clearMetaKeyStates(
                KeyEvent.META_ALT_ON or
                        KeyEvent.META_ALT_LEFT_ON or
                        KeyEvent.META_ALT_RIGHT_ON
            )
        }
        val effectiveEvent = withInjectedModifiers(event)
        return forwardKeyEvent(effectiveEvent) || super.onKeyUp(keyCode, effectiveEvent)
    }

    // 长按键帽符号的 pending 数据。inner class 内禁止再嵌套 class（Kotlin 限制），故放在服务层；
    // HardwareKeyDispatch 作为内部类可直接访问这个 private 嵌套类。
    private data class PendingSymbolPress(
        var runnable: Runnable,
        var fired: Boolean,
        var textLenBefore: Int = 0
    )

    // ===== HardwareKeyDispatch：物理键盘按键「自带状态」子策略 =====
    // onKeyDown/onKeyUp 里仅有的两块有内部时序状态的逻辑（Alt-latch 双击锁 + 长按键帽符号）收口于此；
    // 路由 / 探查 / early-return / forwardKeyEvent 仍由服务主函数编排。作为内部类直接复用服务的
    // 私有成员（inputView / fcitx / mainHandler / currentInputConnection …），不引入额外接口。
    private inner class HardwareKeyDispatch {

        // ===== Alt-latch 双击锁状态 =====
        // altLatched / lastAltTapEventTime / altDownStartTime / altLongPressThresholdMs 被服务侧
        // （isAltLatched / isAltLockedOrSticky / withInjectedModifiers / clearAltLatchAndMetaState /
        // onKeyUp 长按 Alt 清 meta）直接读写，故不做 private；其余状态只在本类内流转。
        var altLatched = false
        var lastAltTapEventTime = 0L
        var altDownStartTime = 0L
        val altLongPressThresholdMs = 500L
        private val altDoubleTapTimeoutMs = 300L

        // True when THIS key gesture was consumed by latching (pure latch key, double-tap latch, or
        // unlock). Used so onKeyUp only swallows the key-up for gestures latching actually handled,
        // letting a colliding selection key's own key-up handling run.
        private var altLatchConsumedThisGesture = false

        // ===== 长按键帽符号（BlackBerry 风格）状态 =====
        // Per-key tracking of an in-flight long-press-to-symbol gesture. A Map keyed by keyCode (not a
        // single slot) is required because fast typing can have several letter keys down inside the
        // 400ms window at once; one shared slot would let the second key overwrite the first, dropping
        // the first key's character (its key-down was consumed and never replayed on key-up).
        // `textLenBefore` snapshots the length of the text before the cursor (INCLUDING any preedit) right
        // before this key's down is forwarded, so the long-press handler can measure how many characters
        // the keystroke added — needed to retract them (whether committed or still in preedit) when the
        // long-press fires.
        private val symbolLongPressPending = mutableMapOf<Int, PendingSymbolPress>()

        /**
         * Alt-latch 双击锁 key-down 状态机。
         * @return true = 本次按下被 latch 逻辑消费（调用方 return true）；null = 未消费，继续下行派发。
         */
        fun dispatchAltLatchDown(keyCode: Int, event: KeyEvent, wasAltDown: Boolean): Boolean? {
            // Track Alt press start time (used by the long-press Alt release logic in onKeyUp).
            if (isAnyAltKeyCode(keyCode) && event.repeatCount == 0) {
                altDownStartTime = event.eventTime
            }

            // When Alt latch is disabled, clear any latched state and let Alt behave as a normal modifier.
            if (!altLatchEnabled() && altLatched) {
                setAltLatched(false)
            }

            if (altLatchEnabled() && isAltLatchKey(event)) {
                if (event.repeatCount == 0 && !wasAltDown) {
                    val now = event.eventTime
                    if (altLatched) {
                        // Already latched: pressing the latch key again unlocks it (pure unlock, consume).
                        setAltLatched(false)
                        lastAltTapEventTime = 0L
                        // 同步清掉框架层 sticky meta，防止长按后系统残留的 locked 状态卡住
                        clearSystemAltSticky()
                        Timber.d("Alt latch disabled")
                        altLatchConsumedThisGesture = true
                        return true
                    } else if (lastAltTapEventTime > 0L && now - lastAltTapEventTime <= altDoubleTapTimeoutMs) {
                        // Second tap within the window: latch on. Consume so it does not also select.
                        setAltLatched(true)
                        lastAltTapEventTime = 0L
                        Timber.d("Alt latch enabled")
                        altLatchConsumedThisGesture = true
                        return true
                    } else {
                        // First tap: start the double-tap timer.
                        lastAltTapEventTime = now
                        // If this physical key is ALSO a configured selection / symbol / paging shortcut,
                        // let the single press fall through to selection instead of being swallowed by
                        // latching (otherwise the selection key stops working). A pure latch key (e.g. the
                        // default Alt_L) is consumed here so a lone Alt press never leaks the Alt modifier
                        // into fcitx5.
                        if (inputView?.isHardwareShortcutKey(event) != true) {
                            altLatchConsumedThisGesture = true
                            // 关键：消费单次 Alt 键时主动清掉框架可能残留的 sticky meta。
                            // 长按 Alt 后系统可能进入 locked，单按 Alt 命中 first-tap 分支消费
                            // 掉后框架 locked 状态仍卡住，必须显式清掉。
                            clearSystemAltSticky()
                            return true
                        }
                        // Otherwise fall through; downstream selection logic handles this press.
                    }
                }
                // Long-press repeats (repeatCount > 0) and colliding selection keys: let them through.
            }

            if (altLatchEnabled() && event.repeatCount == 0 && altLatched && isAltUnlockKeyCode(keyCode)) {
                setAltLatched(false)
                lastAltTapEventTime = 0L
                // Alt/Space/Enter 解锁时也清掉框架层 sticky meta
                clearSystemAltSticky()
                Timber.d("Alt latch disabled by keyCode=$keyCode")
                // Alt key itself acts as a pure unlock action.
                if (isAnyAltKeyCode(keyCode)) return true
            }
            return null
        }

        /**
         * Alt-latch key-up。
         * @return true/false = 结束 onKeyUp（latch 键的 up 不再下发）；null = 非 latch 键，继续下行。
         */
        fun dispatchAltLatchUp(keyCode: Int, event: KeyEvent): Boolean? {
            if (altLatchEnabled() && isAltLatchKey(event)) {
                // Only swallow the key-up when THIS gesture was consumed by latching (pure latch key,
                // double-tap latch, or unlock). Otherwise let it through so the selection key's own
                // key-up handling (consumedHardwareCandidateShortcutKeys) applies.
                if (altLatchConsumedThisGesture) {
                    altLatchConsumedThisGesture = false
                    return true
                }
                return false
            }
            return null
        }

        /**
         * 长按键帽符号（BlackBerry 风格）key-down：合格键登记 pending 并启动长按定时器。
         * 总是 fall-through —— 按键的普通字符照常上屏，到阈值仍按住才由定时器替换为键帽符号。
         */
        fun armLongPressSymbol(keyCode: Int, event: KeyEvent) {
            // Skip only when Alt is *physically held* (physicalAltDown) or *app-level latched*
            // (altLatched, double-tap Alt) so Alt+number keeps selecting candidates and intentional
            // Alt mode is preserved. We deliberately do NOT check systemAltSticky here: that flag is
            // the framework's own Alt-lock artifact (e.g. Q25 locks Alt on a long-press of the Alt
            // key itself), not a deliberate Alt+key combo — the user still expects keycap symbols when
            // they long-press a letter after such a lock, so it must not block symbol input.
            // Keys bound to other jobs (0, Shift, SYM/Alt_R, Space) are absent from the map and fall
            // through naturally.
            // Skip in non-text apps (TYPE_NULL: games / emulators) — they hold keys for movement or
            // action and must keep receiving every event; hijacking their physical keys would make
            // held buttons dead (e.g. GBA emulator). The feature is meaningless there anyway.
            if (event.repeatCount == 0 &&
                longPressSymbolEnabled() &&
                !inputDeviceMgr.isNullInputType() &&
                !physicalAltDown && !altLatched &&
                HardwareKeySymbolMap.contains(keyCode)
            ) {
                // 按下即上屏（下方 fall-through forwardKeyEvent 处理），消除"抬起才上屏"的慢半拍；
                // 这里只登记 pending 并启动长按定时器，到阈值仍按住才把刚上屏的字母替换为键帽符号。
                val runnable = Runnable {
                    symbolLongPressPending[keyCode]?.let { pending ->
                        if (!pending.fired) {
                            pending.fired = true
                            HardwareKeySymbolMap.symbolForKeyCode(keyCode)?.let { sym ->
                                mainHandler.post {
                                    // ① 英文/纯文本直上屏(composing 空):字母已 commit 进正文,
                                    //    按按下前后的正文长度差删除。
                                    val delta = if (composing.isEmpty()) {
                                        val text = currentInputConnection?.getTextBeforeCursor(1024, 0)
                                        val now = text?.let { it.toString().codePointCount(0, it.length) }
                                            ?: pending.textLenBefore
                                        (now - pending.textLenBefore).coerceIn(0, 8)
                                    } else {
                                        0
                                    }

                                    if (delta > 0) {
                                        currentInputConnection?.deleteSurroundingText(delta, 0)
                                    }
                                    // ② 长按替换的引擎清理,按输入法分流:
                                    //    - table 引擎(五笔/自然码/仓颉等):reset() 会把 preedit commit 上屏
                                    //      (TableEngine::reset + commitWhenDeactivate),必须先 BackSpace
                                    //      清空 preedit(数量=clientPreeditCached 长度,该缓存是引擎在
                                    //      客户端声明 CapabilityFlag::Preedit 时写入的 clientPreedit),
                                    //      再 reset、再 commit。
                                    //    - 非 table(拼音/英文/纯文本):引擎 reset 只清面板不 commit
                                    //      (PinyinEngine::doReset 只 reset panel + updatePreedit),直接
                                    //      reset + commit 即可。**绝不能用 BackSpace**——数量不准会转发
                                    //      客户端吞掉正文(拼音吞符号回归的根因)。
                                    if (isTableIme()) {
                                        val (clientPre, panelPre) = fcitx.runImmediately {
                                            clientPreeditCached.toString() to
                                                    inputPanelCached.preedit.toString()
                                        }
                                        val clientLen =
                                            clientPre.codePointCount(0, clientPre.length)
                                        val panelLen =
                                            panelPre.codePointCount(0, panelPre.length)
                                        val preeditStr =
                                            if (clientLen >= panelLen) clientPre else panelPre
                                        val preeditLen =
                                            preeditStr.codePointCount(0, preeditStr.length)

                                        postFcitxJob {
                                            repeat(preeditLen.coerceIn(0, 8)) {
                                                sendKey(
                                                    KeySym(FcitxKeyMapping.FcitxKey_BackSpace),
                                                    KeyStates.Virtual,
                                                    0
                                                )
                                            }
                                            if (!isEmpty()) reset()
                                            withContext(Dispatchers.Main) { commitText(sym) }
                                        }
                                    } else {
                                        postFcitxJob {
                                            if (!isEmpty()) reset()
                                            withContext(Dispatchers.Main) { commitText(sym) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                symbolLongPressPending[keyCode] = PendingSymbolPress(
                    runnable,
                    false,
                    textLengthBeforeCursor()
                )
                mainHandler.postDelayed(runnable, longPressSymbolThresholdMs())
            }
        }

        /**
         * 长按键帽符号 key-up 消解：移除并取消本键 pending。
         * @return true = 已长按 fired（符号已发出，调用方吞掉 up 避免字母再上屏一次）；false = 短按，继续下行。
         */
        fun resolveLongPressSymbolUp(keyCode: Int): Boolean {
            symbolLongPressPending.remove(keyCode)?.let { pending ->
                mainHandler.removeCallbacks(pending.runnable)
                if (pending.fired) {
                    return true
                }
            }
            return false
        }

        fun isLongPressPending(keyCode: Int): Boolean =
            symbolLongPressPending.containsKey(keyCode)

        /** 丢弃某个 keyCode 上待定的「长按键帽符号」（快捷键消费 DOWN 时必须调用，否则长按会冒符号）。 */
        fun cancelLongPressSymbol(keyCode: Int) {
            symbolLongPressPending.remove(keyCode)?.let { mainHandler.removeCallbacks(it.runnable) }
        }

        fun cancelAllLongPressSymbols() {
            symbolLongPressPending.values.forEach { mainHandler.removeCallbacks(it.runnable) }
            symbolLongPressPending.clear()
        }
    }

    /**
     * Fallback landing spot for the keyboard surface's motion, kept armed for the ROMs/modes that
     * route it through the service instead of the decor view — the primary channel is
     * [installDecorMotionListener], which consumes the stream before it ever gets here (so this
     * never double-feeds). Also the Lab probe's entry point: in "pointer / mouse" mode the surface
     * reports hover and relative-axis motion here rather than through the touch path.
     *
     * Feed on the same terms as the decor listener: armed, or a gesture already in flight that still
     * needs its UP/CANCEL.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        TouchProbeLog.record(TouchProbeLog.PATH_IME_MOTION, event)
        // Only the keyboard-surface touch stream (SOURCE_TOUCHPAD == 0x100008, the source the
        // device reports for dev=6 while the IME is active) belongs to fly-text; hover / scroll
        // from other devices must keep falling through to super, per the "==" source rule.
        if (flyTextSelectorInitialized &&
            (flyTextOn || flyTextCornerDeleteOn || flyTextSelector.gestureActive
                    || flyTextPickerPagingOn || flyTextCursorOn) &&
            event.source == InputDevice.SOURCE_TOUCHPAD
        ) {
            flyTextSelector.onTouchEvent(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceMgr.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        // While the keyboard-surface capture window is up, a finger touch (screen OR keyboard
        // surface) is a capture gesture — not a request to summon the soft keyboard. Skip the
        // mode flip so the gesture can't dismiss/reconfigure the capture setup mid-swipe.
        if (keyboardSurfaceProbing) return
        inputDeviceMgr.evaluateOnUpdateEditorToolType(toolType, this)
    }

    private var firstBindInput = true
    private var lastNonNullStartInputViewUptime: Long = 0L
    private var suppressTransientFinishInputView: Boolean = false

    override fun onBindInput() {
        val uid = currentInputBinding.uid
        val pkgName = pkgNameCache.forUid(uid)
        Timber.d("onBindInput: uid=$uid pkg=$pkgName")
        postFcitxJob {
            // ensure InputContext has been created before focusing it
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype)
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }

    /**
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    private var skipNextSubtypeChange: String? = null

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im")
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            if (skipNextSubtypeChange == im) {
                skipNextSubtypeChange = null
                return
            }
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // update selection as soon as possible
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated. but it's the client app's responsibility to send
        // right cursor position, try to workaround this would simply introduce more bugs.
        selection.resetTo(attribute.initialSelStart, attribute.initialSelEnd)
        resetComposingState()
        val flags = CapabilityFlags.fromEditorInfo(attribute)
        capabilityFlags = flags
        // EditorInfo may change between onStartInput and onStartInputView
        inputDeviceMgr.notifyOnStartInput(attribute)
        Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
        val isNullType = attribute.isTypeNull()
        val isTransientNullRestart =
            restarting && isNullType && currentInputStarted &&
                    (SystemClock.uptimeMillis() - lastNonNullStartInputViewUptime) < 1500
        // wait until InputContext created/activated
        postFcitxJob {
            if (isTransientNullRestart) {
                Timber.d("onStartInput: ignore transient TYPE_NULL restarting input")
                return@postFcitxJob
            }
            if (restarting && !isNullType) {
                // when input restarts in the same editor, focus out to clear previous state
                focus(false)
                // try focus out before changing CapabilityFlags,
                // to avoid confusing state of different text fields
            } else if (restarting) {
                Timber.d("onStartInput: skip focus(false) for TYPE_NULL restarting input")
            }
            // EditorInfo can be different in onStartInput and onStartInputView,
            // especially in browsers
            setCapFlags(flags)
            // for hardware keyboard, focus to allow switching input methods before onStartInputView
            if (!isNullType) {
                focus(true)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        val isNullType = info.isTypeNull()
        val now = SystemClock.uptimeMillis()
        Timber.d(
            "onStartInputView: restarting=$restarting inputType=${info.inputType} imeOptions=${info.imeOptions} isNullType=$isNullType"
        )
        if (!isNullType) {
            lastNonNullStartInputViewUptime = now
            suppressTransientFinishInputView = false
        }
        if (restarting && isNullType && currentInputStarted &&
            (now - lastNonNullStartInputViewUptime) < 1500
        ) {
            suppressTransientFinishInputView = true
            Timber.d("onStartInputView: suppress transient TYPE_NULL restarting inputView")
            decorView.post {
                if (currentInputStarted) {
                    forceShowSelf()
                }
            }
            return
        }
        postFcitxJob {
            focus(true)
        }
        if (inputDeviceMgr.evaluateOnStartInputView(info, this)) {
            // because onStartInputView will always be called after onStartInput,
            // editorInfo and capFlags should be up-to-date
            inputView?.startInput(info, capabilityFlags, restarting)
            if (!restarting) {
                updateInputViewShown()
            }
        } else {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        // Re-starting an input session is another chance for the event channel to have been lost
        // while the mode itself never changed (nothing else re-pushes it in that case).
        inputDeviceMgr.reapplyMode()
        // Re-apply the keyboard-surface state now that the IME is up: arm fly-text if candidates are
        // already on screen, and install the decor motion channel.
        refreshFlyTextState()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        // onUpdateSelection can left behind when user types quickly enough, eg. long press backspace
        cursorUpdateIndex += 1
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd]")
        handleCursorUpdate(newSelStart, newSelEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = contentView.height.toFloat()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal =
                if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition[0] = horizontal
            anchorPosition[1] = bounds.bottom
            anchorPosition[2] = horizontal
            anchorPosition[3] = bounds.top
        } else {
            anchorPosition[0] = info.insertionMarkerHorizontal
            anchorPosition[1] = info.insertionMarkerBottom
            anchorPosition[2] = info.insertionMarkerHorizontal
            anchorPosition[3] = info.insertionMarkerTop
        }
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(anchorPosition)
        val (xOffset, yOffset) = decorLocation
        anchorPosition[0] -= xOffset
        anchorPosition[1] -= yOffset
        anchorPosition[2] -= xOffset
        anchorPosition[3] -= yOffset
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    private fun handleCursorUpdate(newSelStart: Int, newSelEnd: Int, updateIndex: Int) {
        if (selection.consume(newSelStart, newSelEnd)) {
            return // do nothing if prediction matches
        } else {
            // cursor update can't match any prediction: it's treated as a user input
            selection.resetTo(newSelStart, newSelEnd)
        }
        // skip selection range update, we only care about selection cursor (zero width) here
        if (newSelStart != newSelEnd) return
        // do reset if composing is empty && input panel is not empty
        if (composing.isEmpty()) {
            postFcitxJob {
                if (!isEmpty()) {
                    Timber.d("handleCursorUpdate: reset")
                    reset()
                }
            }
            return
        }
        // check if cursor inside composing text
        if (composing.contains(newSelStart)) {
            if (ignoreSystemCursor) return
            // fcitx cursor position is relative to client preedit (composing text)
            val position = newSelStart - composing.start
            // move fcitx cursor when cursor position changed
            if (position != composingText.cursor) {
                // cursor in InvokeActionEvent counts by "UTF-8 characters"
                val codePointPosition = composingText.codePointCountUntil(position)
                postFcitxJob {
                    if (updateIndex != cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to $codePointPosition")
                    moveCursor(codePointPosition)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: focus out/in")
            resetComposingState()
            // cursor outside composing range, finish composing as-is
            currentInputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            postFcitxJob {
                focusOutIn()
            }
        }
    }

    // because setComposingText(text, cursor) can only put cursor at end of composing,
    // sometimes onUpdateSelection would receive event with wrong cursor position.
    // those events need to be filtered.
    // because of https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r45/core/java/android/view/inputmethod/BaseInputConnection.java#851
    // it's not possible to set cursor inside composing text
    private fun updateComposingText(text: FormattedText) {
        val ic = currentInputConnection ?: return
        val lastSelection = selection.latest
        ic.beginBatchEdit()
        if (composingText.spanEquals(text)) {
            // composing text content is up-to-date
            // update cursor only when it's not empty AND cursor position is valid
            if (text.length > 0 && text.cursor >= 0) {
                val p = text.cursor + composing.start
                if (p != lastSelection.start) {
                    Timber.d("updateComposingText: set Android selection ($p, $p)")
                    ic.setSelection(p, p)
                    selection.predict(p)
                }
            }
        } else {
            // composing text content changed
            Timber.d("updateComposingText: '$text' lastSelection=$lastSelection")
            if (text.isEmpty()) {
                if (composing.isEmpty()) {
                    // do not reset saved selection range when incoming composing
                    // and saved composing range are both empty:
                    // composing.start is invalid when it's empty.
                    selection.predict(lastSelection.start)
                } else {
                    // clear composing text, put cursor at start of original composing
                    selection.predict(composing.start)
                    composing.clear()
                }
                ic.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                // skip cursor reposition when:
                // - preedit cursor is at the end
                // - cursor position is invalid
                val spanned = text.toSpannedString(highlightColor)
                if (text.cursor == text.length || text.cursor < 0) {
                    selection.predict(composing.end)
                    ic.setComposingText(spanned, 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    ic.setComposingText(spanned, 1)
                    ic.setSelection(p, p)
                }
            }
            Timber.d("updateComposingText: composing=$composing")
        }
        composingText = text
        ic.endBatchEdit()
    }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        composingText = FormattedText.Empty
        ic.finishComposingText()
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // ignore inline suggestion when disabled by user || using physical keyboard with floating candidates view
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return null
        val theme = ThemeManager.activeTheme
        if (theme === cachedInlineSuggestionTheme && cachedInlineSuggestionRequest != null) {
            return cachedInlineSuggestionRequest
        }
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        val request = InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
        cachedInlineSuggestionTheme = theme
        cachedInlineSuggestionRequest = request
        return request
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d(
            "onFinishInputView: finishingInput=$finishingInput currentInputStarted=${currentInputStarted} isInputViewShown=${isInputViewShown}"
        )
        if (suppressTransientFinishInputView && !finishingInput && currentInputStarted) {
            suppressTransientFinishInputView = false
            Timber.d("onFinishInputView: ignored transient finishInputView(false)")
            decorView.post {
                if (currentInputStarted) {
                    forceShowSelf()
                }
            }
            return
        }
        decorLocationUpdated = false
        inputDeviceMgr.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        resetComposingState()
        // Avoid disrupting transient editor focus (e.g. floating search panels) when
        // only the input view is being hidden but the input session is still alive.
        if (finishingInput) {
            postFcitxJob {
                focusOutIn()
            }
        }
        hideStatusIcon()
        showingDialog?.dismiss()
        // Drop any in-flight fly-text gesture so a stale slide can't latch the next session. There
        // is no window to tear down: the decor motion channel belongs to the IME window and goes
        // away with it.
        if (flyTextSelectorInitialized) flyTextSelector.reset()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput: currentInputStarted=$currentInputStarted isInputViewShown=$isInputViewShown")
        clearAltLatchAndMetaState()
        hardwareKeyDispatch.cancelAllLongPressSymbols()
        // 会话结束：Fn/Sym 按住状态与 tap-hold 挂起都作废，避免跨会话僵死。
        HardwareChord.reset()
        postFcitxJob {
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        cachedKeyEvents.evictAll()
        hardwareKeyDispatch.cancelAllLongPressSymbols()
        // 同上：解绑输入也要清伪修饰键状态（跨会话僵死会把普通字母全判成和弦）。
        HardwareChord.reset()
        cachedKeyEventIndex = 0
        cursorUpdateIndex = 0
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        val uid = currentInputBinding?.uid ?: return
        Timber.d("onUnbindInput: uid=$uid")
        postFcitxJob {
            deactivate(uid)
        }
    }

    override fun onDestroy() {
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        effectsOverlay?.release()
        effectsOverlay = null
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"
        const val KeyCaptureFlag = "org.fcitx.fcitx5.android.KEY_CAPTURE"
    }
}
