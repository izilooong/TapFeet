/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.core.FcitxEvent.InputPanelEvent
import org.fcitx.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.ScopeEvent
import org.mechdancer.dependency.UniqueComponent
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

class InputBroadcaster : UniqueComponent<InputBroadcaster>(), Dependent, InputBroadcastReceiver {

    /**
     * Fault-isolated fan-out. Each receiver is attempted independently so a single receiver throwing
     * can never abort the [ConcurrentLinkedQueue.forEach] and starve the other receivers (e.g. the
     * candidate bar) of the same event — that starvation used to manifest as a frozen candidate bar
     * under high-frequency typing while typing/selecting kept working. A failure is logged (tag
     * CandSync) for root-cause follow-up but never propagates out of the broadcast.
     */
    private inline fun dispatch(crossinline block: InputBroadcastReceiver.() -> Unit) {
        receivers.forEach { receiver ->
            runCatching { receiver.block() }
                .onFailure { e -> Timber.e(e, "CandSync: receiver dispatch failed: ${receiver::class.java.simpleName}") }
        }
    }


    private val receivers = ConcurrentLinkedQueue<InputBroadcastReceiver>()

    /**
     * Receiver 生命周期的唯一 seam。注册与注销都收在这里，broadcaster 因此持有集合语义，
     * 而不只是把 15 个回调逐个转发；测试也通过它布置场景，不必再反射进私有队列
     * （the interface is the test surface）。
     */
    fun addReceiver(receiver: InputBroadcastReceiver) {
        receivers.add(receiver)
    }

    fun removeReceiver(receiver: InputBroadcastReceiver) {
        receivers.remove(receiver)
    }

    override fun handle(scopeEvent: ScopeEvent) {
        when (scopeEvent) {
            is ScopeEvent.DependencyArrivedEvent -> {
                val dependency = scopeEvent.dependency
                if (dependency is InputBroadcastReceiver && dependency !is InputBroadcaster) {
                    addReceiver(dependency)
                }
            }

            is ScopeEvent.DependencyLeftEvent -> {
                val dependency = scopeEvent.dependency
                if (dependency is InputBroadcastReceiver && dependency !is InputBroadcaster) {
                    removeReceiver(dependency)
                }
            }
        }
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        dispatch { onClientPreeditUpdate(data) }
    }

    override fun onInputPanelUpdate(data: InputPanelEvent.Data) {
        dispatch { onInputPanelUpdate(data) }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        dispatch { onStartInput(info, capFlags) }
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        dispatch { onImeUpdate(ime) }
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        dispatch { onCandidateUpdate(data) }
    }

    override fun onPagedCandidateUpdate(data: PagedCandidateEvent.Data) {
        dispatch { onPagedCandidateUpdate(data) }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        dispatch { onStatusAreaUpdate(actions) }
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        dispatch { onSelectionUpdate(start, end) }
    }

    override fun onWindowAttached(window: InputWindow) {
        dispatch { onWindowAttached(window) }
    }

    override fun onWindowDetached(window: InputWindow) {
        dispatch { onWindowDetached(window) }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        dispatch { onScopeSetupFinished(scope) }
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        dispatch { onPunctuationUpdate(mapping) }
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        dispatch { onPreeditEmptyStateUpdate(empty) }
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        dispatch { onReturnKeyDrawableUpdate(resourceId) }
    }

    override fun onCommitText(text: String) {
        dispatch { onCommitText(text) }
    }

}