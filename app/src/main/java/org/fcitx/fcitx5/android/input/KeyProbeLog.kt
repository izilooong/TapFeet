/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import android.view.KeyEvent
import org.fcitx.fcitx5.android.data.prefs.HardwareSpecialKeys
import org.fcitx.fcitx5.android.input.candidates.HardwareShortcutResolver
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process probe of the key events the **input method** actually receives.
 *
 * Why this exists: the Lab page's own key listener sits on the app window, so it only ever sees the
 * keys that survive the framework's system-key interception. Physical function keys such as
 * `home` / `recents` are consumed by the system *before* any window — including the IME window —
 * gets them, so "why does my key binding do nothing?" cannot be answered from the app side. This
 * probe is written from [FcitxInputMethodService.onKeyDown]/`onKeyUp`, i.e. from the exact place the
 * shortcut matching runs, so it shows the ground truth: which keyCode/scanCode arrives (if at all),
 * whether it is a registered pseudo key, and whether any configured shortcut claims it.
 *
 * [FcitxInputMethodService] and the settings UI share one process, so a plain singleton is enough.
 */
object KeyProbeLog {

    /** One key event as seen by the IME, plus the conclusions the IME can draw about it. */
    data class Entry(
        val seq: Int,
        val time: Long,
        val keyCode: Int,
        val scanCode: Int,
        val action: Int,
        val metaState: Int,
        val repeatCount: Int,
        /** Name of the matching [HardwareSpecialKeys] entry, or null for an ordinary key. */
        val specialName: String?,
        /** Whether any configured hardware shortcut key (candidate / paging / sym / global) matches. */
        val isShortcutKey: Boolean,
    ) {
        /**
         * `#seq ACTION NAME code=.. scan=.. [pseudo=.. shortcut repeat=.. meta=..]`
         *
         * `pseudo=` names the [HardwareSpecialKeys] entry the key resolves to — the thing a physical
         * function-key binding depends on — and `shortcut` marks keys claimed by any configured
         * hardware shortcut. A key the user presses but which never shows up here did not reach the
         * input method at all.
         */
        fun toLine(): String {
            val actionName = when (action) {
                KeyEvent.ACTION_DOWN -> "DOWN"
                KeyEvent.ACTION_UP -> "UP"
                else -> "ACTION_$action"
            }
            val notes = buildList {
                specialName?.let { add("pseudo=$it") }
                if (isShortcutKey) add("shortcut")
                if (repeatCount > 0) add("repeat=$repeatCount")
                if (metaState != 0) add("meta=0x${metaState.toString(16)}")
            }
            return buildString {
                append("#$seq $actionName ${KeyEvent.keyCodeToString(keyCode)}")
                append(" code=$keyCode scan=$scanCode")
                if (notes.isNotEmpty()) append(" [${notes.joinToString(" ")}]")
            }
        }
    }

    private const val MAX_ENTRIES = 60

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var seq = 0

    /**
     * Off by default: this is a diagnostic probe, so it only records while the Lab page is showing
     * (the fragment flips it in onResume/onPause). Turning it off also drops the backlog.
     */
    @Volatile
    var recording: Boolean = false
        set(value) {
            field = value
            // Turning the probe off drops the backlog, so a stale log can never be mistaken for
            // live data.
            if (!value) clear()
        }

    fun record(event: KeyEvent) {
        if (!recording) return
        val entry = Entry(
            seq = ++seq,
            time = SystemClock.elapsedRealtime(),
            keyCode = event.keyCode,
            scanCode = event.scanCode,
            action = event.action,
            metaState = event.metaState,
            repeatCount = event.repeatCount,
            specialName = HardwareSpecialKeys.entryForKeyCode(event.keyCode)?.name,
            isShortcutKey = HardwareShortcutResolver.isHardwareShortcutKey(event),
        )
        synchronized(lock) {
            entries.addFirst(entry)
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
        // Also to logcat ("KeyProbe:" in the body — never filter by tag, VerboseTree rewrites it),
        // so the probe can be read over adb without looking at the screen.
        Timber.i("KeyProbe: ${entry.toLine()}")
        notifyChanged()
    }

    /** Newest first. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    // Notified outside the lock: listeners touch views and must not run under it.
    private fun notifyChanged() {
        listeners.forEach { it() }
    }
}
