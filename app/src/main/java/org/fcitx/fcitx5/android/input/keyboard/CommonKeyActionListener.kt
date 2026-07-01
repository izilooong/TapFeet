/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private var backspaceSwipeState = Stopped

    private fun localCandidateNumber(action: KeyAction): Int? {
        return when (action) {
            is FcitxKeyAction -> {
                if (action.act.length != 1) return null
                val char = action.act[0]
                when {
                    char == ' ' -> 1
                    char.isDigit() -> char.digitToInt()
                    else -> null
                }
            }
            is SymAction -> when {
                action.sym.sym == FcitxKeyMapping.FcitxKey_space ||
                    action.sym.keyCode == KeyEvent.KEYCODE_SPACE -> 1
                action.sym.sym == '0'.code || action.sym.sym == 0xffb0 -> 0
                action.sym.sym == '1'.code || action.sym.sym == 0xffb1 -> 1
                action.sym.sym == '2'.code || action.sym.sym == 0xffb2 -> 2
                action.sym.sym == '3'.code || action.sym.sym == 0xffb3 -> 3
                action.sym.sym == '4'.code || action.sym.sym == 0xffb4 -> 4
                action.sym.sym == '5'.code || action.sym.sym == 0xffb5 -> 5
                action.sym.sym == '6'.code || action.sym.sym == 0xffb6 -> 6
                action.sym.sym == '7'.code || action.sym.sym == 0xffb7 -> 7
                action.sym.sym == '8'.code || action.sym.sym == 0xffb8 -> 8
                action.sym.sym == '9'.code || action.sym.sym == 0xffb9 -> 9
                else -> null
            }
            else -> null
        }
    }

    private fun handleLocalSubPageShortcut(action: KeyAction): Boolean {
        if (!horizontalCandidate.isOnLocalSubPage()) return false
        val number = localCandidateNumber(action) ?: return false
        return commitLocalSubPageCandidate(number)
    }

    private fun commitLocalSubPageCandidate(number: Int): Boolean {
        if (!horizontalCandidate.isOnLocalSubPage()) return false
        val candidate = horizontalCandidate.candidateForLocalNumber(number) ?: return false
        service.finishComposing()
        service.commitText(candidate.text)
        return true
    }

    private suspend fun FcitxAPI.selectCurrentLocalCandidate(number: Int): Boolean {
        if (horizontalCandidate.isOnLocalSubPage()) {
            return commitLocalSubPageCandidate(number)
        }
        val index = horizontalCandidate.selectionIndexForLocalNumber(number) ?: return false
        setCandidatePagingMode(0)
        return select(index)
    }

    private suspend fun FcitxAPI.handleLocalCandidateShortcut(action: FcitxKeyAction): Boolean {
        if (action.act.length != 1) return false
        val char = action.act[0]
        if (char == ' ') return selectCurrentLocalCandidate(1)
        if (!char.isDigit()) return false
        return selectCurrentLocalCandidate(char.digitToInt())
    }

    private suspend fun FcitxAPI.handleLocalCandidateShortcut(action: SymAction): Boolean {
        if (
            action.sym.sym == FcitxKeyMapping.FcitxKey_space ||
            action.sym.keyCode == KeyEvent.KEYCODE_SPACE
        ) {
            return selectCurrentLocalCandidate(1)
        }
        return when (val sym = action.sym.sym) {
            '0'.code, 0xffb0 -> selectCurrentLocalCandidate(0)
            '1'.code, 0xffb1 -> selectCurrentLocalCandidate(1)
            '2'.code, 0xffb2 -> selectCurrentLocalCandidate(2)
            '3'.code, 0xffb3 -> selectCurrentLocalCandidate(3)
            '4'.code, 0xffb4 -> selectCurrentLocalCandidate(4)
            '5'.code, 0xffb5 -> selectCurrentLocalCandidate(5)
            '6'.code, 0xffb6 -> selectCurrentLocalCandidate(6)
            '7'.code, 0xffb7 -> selectCurrentLocalCandidate(7)
            '8'.code, 0xffb8 -> selectCurrentLocalCandidate(8)
            '9'.code, 0xffb9 -> selectCurrentLocalCandidate(9)
            else -> {
                if (action.sym.keyCode == KeyEvent.KEYCODE_SPACE) {
                    selectCurrentLocalCandidate(1)
                } else {
                    false
                }
            }
        }
    }

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate, except prediction candidates
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                // preedit not empty, maybe there are candidates to select ...
                if (!selectCurrentLocalCandidate(1)) {
                    select(0)
                }
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            if (handleLocalSubPageShortcut(action)) {
                return@KeyActionListener
            }
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    if (!handleLocalCandidateShortcut(action)) {
                        sendKey(action.act, action.states.states, action.code)
                    }
                }
                is SymAction -> service.postFcitxJob {
                    if (!handleLocalCandidateShortcut(action)) {
                        sendKey(action.sym, action.states)
                    }
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    enumerateIme()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                    }
                }
                else -> {}
            }
        }
    }
}
