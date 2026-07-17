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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
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
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
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

    private val navbarMgr = NavigationBarManager()
    private val inputDeviceMgr = InputDeviceManager { isVirtualKeyboard ->
        postFcitxJob {
            setCandidatePagingMode(if (isVirtualKeyboard) 0 else 1)
        }
        currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard)
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
        setInputView(newInputView)
        inputDeviceMgr.setInputView(newInputView)
        inputView = newInputView
        newInputView.onAltLatchChanged(altLatched)
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        return newCandidatesView
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
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
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

    fun commitText(text: String, cursor: Int = -1) {
        val ic = currentInputConnection ?: return
        inputView?.onCommitText(text)
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
        }
        lastKnownConfig = newConfig
    }

    override fun onWindowShown() {
        super.onWindowShown()
        try {
            highlightColor = styledColor(android.R.attr.colorAccent).alpha(0.4f)
        } catch (_: Exception) {
            Timber.w("Device does not support android.R.attr.colorAccent which it should have.")
        }
        InputFeedbacks.syncSystemPrefs()
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

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceMgr.isVirtualKeyboard) {
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

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onEvaluateFullscreenMode() = false

    private var altLatched = false
    private var lastAltTapEventTime = 0L
    private val altDoubleTapTimeoutMs = 300L

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
     * 长按 Alt 启发式检测用的状态。
     *
     * 单纯靠非 Alt 键的 metaState 检测有一个盲区：用户长按 Alt → 松开 → 直接短按 Alt
     * （没按任何其他键），整个流程走的是 first-tap 分支，metaState 检测根本没机会跑。
     * 这时用"按住时长 + 期间是否按过其他键"做启发式补救：
     * - [altDownStartTime]: Alt 按下时的事件时间
     * - [altHadOtherKeysDuringHold]: Alt 按住期间是否按过非 Alt 键（用来排除"按住 Alt 输
     *   入组合键"的正常用法，避免误判）
     */
    private var altDownStartTime = 0L
    private val altLongPressThresholdMs = 500L
    private var altHadOtherKeysDuringHold = false

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
    // True when THIS key gesture was consumed by latching (pure latch key, double-tap latch, or
    // unlock). Used so onKeyUp only swallows the key-up for gestures latching actually handled,
    // letting a colliding selection key's own key-up handling run.
    private var altLatchConsumedThisGesture = false

    fun isAltLatched(): Boolean = altLatched

    /** 框架/编辑器层是否处于 Alt sticky 状态（独立于应用层 latch）。 */
    fun isSystemAltSticky(): Boolean = systemAltSticky

    /** 应用层 latch 或框架层 sticky 任意一个为 true 都算 Alt 处于"锁定"展示态。 */
    fun isAltLockedOrSticky(): Boolean = altLatched || systemAltSticky

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

    fun toggleAltLatch() {
        setAltLatched(!altLatched)
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
        if (altLatched == locked) return
        altLatched = locked
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
        lastAltTapEventTime = 0L
    }

    private fun isAnyAltKeyCode(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
    }

    /**
     * Whether [event] is the configured Alt-latch trigger key
     * (setting: hardwareKeyboard.altLatchKey, default "Alt_L").
     *
     * The latch key is a bare physical key press (typically a modifier like Alt_L), so it is matched
     * by the keysym derived from the event's keyCode. The special "Sym" string maps to the
     * BlackBerry SYM key. An empty configured value disables latching entirely.
     */
    // Self-invalidating memo of the parsed alt-latch key. Reparsing only happens when the
    // configured string actually changes (rare), instead of on every physical key press.
    private var cachedAltLatchString: String? = null
    private var cachedAltLatchKey: Key? = null

    private fun isAltLatchKey(event: KeyEvent): Boolean {
        val keyString = AppPrefs.getInstance().hardwareKeyboard.altLatchKey.getValue()
        if (keyString.isEmpty()) return false
        if (keyString == "Sym") {
            return event.keyCode == KeyEvent.KEYCODE_SYM || event.keyCode == KeyEvent.KEYCODE_PICTSYMBOLS
        }
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
     */
    private fun withInjectedModifiers(event: KeyEvent): KeyEvent {
        var meta = event.metaState
        if (physicalAltDown || altLatched) {
            meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // When the target editor requests key capture (e.g. KeyCaptureUi/KeyPreferenceUi),
        // do not consume physical key events so they reach the EditText's OnKeyListener.
        if (currentInputEditorInfo.privateImeOptions?.contains(KeyCaptureFlag) == true) {
            return false
        }

        // ========== Earliest metaState probe ==========
        // 在任何处理（包括 updatePhysicalModifiers）之前检查 keyEvent 自带的 metaState。
        // 此时 physicalAltDown 仍反映"本次按键之前"的状态。
        // 如果 metaState 已经有 META_ALT_ON 但 physicalAltDown 是 false，说明 Alt
        // 不是用户刚按的，是系统层早就 active（sticky/latched/locked）。
        //
        // 关键限制：只检测**非 Alt 键**。BlackBerry SYM 键在 Android 里走
        // KEYCODE_ALT_RIGHT，按下时 metaState 自带 META_ALT_ON（系统把它当 Alt），
        // 但 SYM 不是真 Alt，不应该触发 sticky 判定。同理物理按 ALT_L/ALT_R
        // 也会让 metaState 带 META_ALT_ON，但不是"系统早就 active"。
        if (!isAnyAltKeyCode(keyCode) && event.repeatCount == 0) {
            val rawAltMeta = (event.metaState and KeyEvent.META_ALT_ON) != 0
            if (rawAltMeta && !physicalAltDown && !systemAltSticky) {
                setSystemAltSticky(true)
                Timber.d("Earliest metaState probe: key=$keyCode metaState=0x${event.metaState.toString(16)} → system Alt sticky")
            }
        }

        // Track physical modifier state from the raw stream (authoritative for combo matching).
        updatePhysicalModifiers(keyCode, true)

        // Track long-press heuristic: record Alt press start time and reset the
        // "other keys during hold" flag. Also flag any non-Alt key pressed while Alt
        // is physically held (so the heuristic won't fire for normal Alt-combo usage).
        if (isAnyAltKeyCode(keyCode) && event.repeatCount == 0) {
            altDownStartTime = event.eventTime
            altHadOtherKeysDuringHold = false
        } else if (!isAnyAltKeyCode(keyCode) && physicalAltDown && event.repeatCount == 0) {
            altHadOtherKeysDuringHold = true
        }

        // Detect system-level Alt sticky state (independent of our app-level altLatched).
        // POSITIVE-ONLY: only use metaState to detect sticky state (set true), never to
        // clear it (set false). Some ROMs (notably the Q25 / certain Android builds) keep
        // the system-level Alt sticky in a native input-dispatcher state that is NOT
        // reflected in the metaState of subsequent regular key events. If we used the
        // absence of META_ALT_ON to clear the flag, the lock icon would disappear the
        // moment the user typed any character — even though the system was still sticky.
        // Clearing must go through explicit [clearSystemAltSticky] (user action).
        //
        // Trigger conditions (any of):
        //   1) Non-Alt key with META_ALT_ON in metaState while Alt NOT physically held
        //      (post-release sticky detection — the normal case)
        //   2) Non-Alt key with META_ALT_ON in metaState while Alt IS physically held
        //      for >= altLongPressThresholdMs (during-hold detection — some ROMs
        //      latch/lock the modifier while the user is still holding it, e.g. the Q25
        //      when the user does "hold Alt + type several symbols then release")
        if (!isAnyAltKeyCode(keyCode) && event.repeatCount == 0) {
            val rawAltMeta = (event.metaState and KeyEvent.META_ALT_ON) != 0
            if (rawAltMeta && !systemAltSticky) {
                val postReleaseSticky = !physicalAltDown
                val duringHoldSticky = physicalAltDown &&
                        (event.eventTime - altDownStartTime >= altLongPressThresholdMs)
                if (postReleaseSticky || duringHoldSticky) {
                    setSystemAltSticky(true)
                    Timber.d("Detected system Alt sticky (postRelease=$postReleaseSticky, duringHold=$duringHoldSticky) from metaState=0x${event.metaState.toString(16)}")
                }
            }
        }

        // When Alt latch is disabled, clear any latched state and let Alt behave as a normal modifier.
        if (!altLatchEnabled() && altLatched) {
            setAltLatched(false)
        }

        if (altLatchEnabled() && isAltLatchKey(event)) {
            if (event.repeatCount == 0) {
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

        val effectiveEvent = withInjectedModifiers(event)

        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceMgr.evaluateOnKeyDown(effectiveEvent, this)) {
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        if (event.repeatCount == 0 && inputView?.handleHardwareCandidateShortcut(effectiveEvent) == true) {
            consumedHardwareCandidateShortcutKeys.add(keyCode)
            return true
        }
        return forwardKeyEvent(effectiveEvent) || super.onKeyDown(keyCode, effectiveEvent)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (currentInputEditorInfo.privateImeOptions?.contains(KeyCaptureFlag) == true) {
            return false
        }

        // Track physical modifier state from the raw stream (authoritative for combo matching).
        updatePhysicalModifiers(keyCode, false)

        // Long-press heuristic for system Alt sticky detection.
        // When Alt is released: if it was held for >= altLongPressThresholdMs, the framework
        // has likely entered sticky/locked state on this device. We do NOT require that no
        // other keys were pressed during the hold — on the Q25 / some Android builds the
        // sticky state is entered even when the user holds Alt and types numbers/symbols
        // (e.g. Alt+number combos held long enough), so excluding that case would miss
        // the real bug. The cost of the occasional false positive is minor: the user just
        // sees a lock icon and can press Alt to clear it.
        //
        // Also: check the metaState of the Alt key-up event itself. On some ROMs the system
        // injects META_ALT_ON into the modifier's own key-up metaState when it has entered
        // sticky/locked state. If we see that (with physicalAltDown now false), it's a
        // strong positive signal even when the duration is short.
        if (isAnyAltKeyCode(keyCode) && event.repeatCount == 0) {
            val duration = event.eventTime - altDownStartTime
            val rawAltMeta = (event.metaState and KeyEvent.META_ALT_ON) != 0
            if (!systemAltSticky) {
                if (duration >= altLongPressThresholdMs) {
                    setSystemAltSticky(true)
                    Timber.d("Heuristic: long-press Alt duration=$duration ms (otherKeys=$altHadOtherKeysDuringHold) → system Alt sticky")
                } else if (rawAltMeta) {
                    setSystemAltSticky(true)
                    Timber.d("Heuristic: Alt key-up metaState carries META_ALT_ON → system Alt sticky")
                }
            }
        }

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
        if (consumedHardwareCandidateShortcutKeys.remove(keyCode)) {
            return true
        }
        val effectiveEvent = withInjectedModifiers(event)
        return forwardKeyEvent(effectiveEvent) || super.onKeyUp(keyCode, effectiveEvent)
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
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput: currentInputStarted=$currentInputStarted isInputViewShown=$isInputViewShown")
        clearAltLatchAndMetaState()
        postFcitxJob {
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        cachedKeyEvents.evictAll()
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
