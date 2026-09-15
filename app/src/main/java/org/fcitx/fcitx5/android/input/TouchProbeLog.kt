/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process probe of touch / pointer motion samples, feeding the Lab page's coordinate test.
 *
 * The physical keyboards these builds target expose their surface as a *separate* input device
 * (`touchPad`, `Sources: KEYBOARD | TOUCHPAD`) and Android may hand its events to several different
 * windows depending on the mode:
 *  - a finger on the surface produces ordinary touch events, delivered to whichever window sits
 *    under the finger — the IME squeezes its touchable region to a sliver in physical-keyboard mode
 *    (`onComputeInsets`), so the app window usually wins;
 *  - the "pointer / mouse" mode produces hover + axis motion that arrives through
 *    `onGenericMotionEvent`, not the touch path;
 *  - the floating candidate window re-enters through [TouchEventReceiverWindow].
 *
 * Recording therefore has to sit at every one of those entry points, and each sample is tagged with
 * the [Entry.path] it came in on. Without that tag, "we see no coordinates" is indistinguishable
 * from "we are listening in the wrong place" — which is exactly the question this probe answers.
 */
object TouchProbeLog {

    /** Path tags, used both as the log tag and as the per-path counters keyed by [counts]. */
    const val PATH_APP_WINDOW = "app"
    const val PATH_APP_MOTION = "app:motion"
    const val PATH_IME_RECEIVER = "ime:recv"
    const val PATH_IME_MOTION = "ime:motion"
    /**
     * IME-side capture of the keyboard's touch surface. When the IME is shown its window is
     * full-screen but only a thin bottom strip is touchable (`onComputeInsets` →
     * `TOUCHABLE_INSETS_VISIBLE`), so a finger on the physical keyboard surface lands in the IME's
     * NON-touchable band and is dropped (not forwarded to the app). [KeyboardSurfaceProbeWindow]
     * makes that band touchable on the IME side and records here, which is the only way the Lab
     * page can see those coordinates while the cursor sits in an input box.
     */
    const val PATH_IME_SURFACE = "ime:surface"

    data class Entry(
        val seq: Int,
        val time: Long,
        val path: String,
        val action: Int,
        val source: Int,
        val deviceId: Int,
        val x: Float,
        val y: Float,
        val rawX: Float,
        val rawY: Float,
        val axisX: Float,
        val axisY: Float,
        val toolType: Int,
        val buttonState: Int,
        val pointerCount: Int,
    ) {
        fun toLine(): String = buildString {
            append("#$seq [").append(path).append("] ").append(actionName(action))
            append(" src=").append(sourceName(source))
            append(" dev=").append(deviceId)
            append(" x=").append(oneDecimal(x)).append(" y=").append(oneDecimal(y))
            append(" raw=").append(oneDecimal(rawX)).append(",").append(oneDecimal(rawY))
            if (axisX != 0f || axisY != 0f) {
                append(" axis=").append(twoDecimals(axisX)).append(",").append(twoDecimals(axisY))
            }
            if (toolType != MotionEvent.TOOL_TYPE_FINGER) {
                append(" tool=").append(toolName(toolType))
            }
            if (pointerCount > 1) append(" ptr=").append(pointerCount)
            if (buttonState != 0) append(" btn=0x").append(buttonState.toString(16))
        }

        companion object {
            fun actionName(a: Int): String = when (a) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
                MotionEvent.ACTION_POINTER_DOWN -> "PTR_DOWN"
                MotionEvent.ACTION_POINTER_UP -> "PTR_UP"
                MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
                MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
                MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
                MotionEvent.ACTION_SCROLL -> "SCROLL"
                MotionEvent.ACTION_BUTTON_PRESS -> "BTN_PRESS"
                MotionEvent.ACTION_BUTTON_RELEASE -> "BTN_RELEASE"
                else -> "ACTION_$a"
            }

            fun toolName(t: Int): String = when (t) {
                MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
                MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
                MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
                MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
                else -> "TOOL_$t"
            }

            /**
             * Raw source bits plus the names that matter here. A touchpad legitimately reports
             * `MOUSE` or `TOUCHSCREEN` depending on the stack (Android 14+ routes it through the
             * ChromeOS touchpad stack), which is precisely what this probe needs to reveal.
             *
             * The comparison must be `==`, not `!= 0`: every pointer source shares the class bit
             * 0x2 (e.g. TOUCHSCREEN = 0x1002, MOUSE = 0x20002), so a `!= 0` test matches all of them
             * at once and labels a plain touchscreen as "TOUCHSCREEN|MOUSE|STYLUS".
             */
            fun sourceName(s: Int): String = buildString {
                append("0x").append(s.toString(16))
                val flags = buildList {
                    if ((s and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
                        add("TOUCHSCREEN")
                    }
                    if ((s and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) add("MOUSE")
                    if ((s and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                        add("TOUCHPAD")
                    }
                    if ((s and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) add("STYLUS")
                }
                if (flags.isNotEmpty()) append("(").append(flags.joinToString("|")).append(")")
            }

            private fun oneDecimal(v: Float) = "%.1f".format(v)
            private fun twoDecimals(v: Float) = "%+.2f".format(v)
        }
    }

    /**
     * Deep enough to hold several *whole* gestures. A single swipe on the keyboard surface samples
     * at ~125Hz and runs 40-90 points, so the old 60-entry cap truncated every stroke after the
     * first — useless once the Lab page started drawing traces instead of just printing numbers.
     */
    private const val MAX_ENTRIES = 800

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val counts = mutableMapOf<String, Int>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var seq = 0

    /**
     * Off by default; the Lab page flips it on in onResume/onPause. Turning it off also drops the
     * backlog and the per-path counters.
     */
    @Volatile
    var recording: Boolean = false
        set(value) {
            field = value
            if (!value) clear()
        }

    fun record(path: String, event: MotionEvent) {
        if (!recording) return
        val entry = Entry(
            seq = ++seq,
            time = SystemClock.elapsedRealtime(),
            path = path,
            action = event.actionMasked,
            source = event.source,
            deviceId = event.deviceId,
            x = event.x,
            y = event.y,
            rawX = event.rawX,
            rawY = event.rawY,
            axisX = event.getAxisValue(MotionEvent.AXIS_X),
            axisY = event.getAxisValue(MotionEvent.AXIS_Y),
            toolType = if (event.pointerCount > 0) {
                event.getToolType(0)
            } else {
                MotionEvent.TOOL_TYPE_UNKNOWN
            },
            buttonState = event.buttonState,
            pointerCount = event.pointerCount,
        )
        synchronized(lock) {
            entries.addFirst(entry)
            while (entries.size > MAX_ENTRIES) entries.removeLast()
            counts[path] = (counts[path] ?: 0) + 1
        }
        // Also to logcat ("TouchProbe:" in the body — never filter by tag, VerboseTree rewrites it).
        Timber.i("TouchProbe: ${entry.toLine()}")
        notifyChanged()
    }

    /** Newest first. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** Total samples seen per [Entry.path] since recording started. */
    fun counts(): Map<String, Int> = synchronized(lock) { counts.toMap() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            counts.clear()
        }
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
