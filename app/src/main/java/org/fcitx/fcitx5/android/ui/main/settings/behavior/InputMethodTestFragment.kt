/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyProbeLog
import org.fcitx.fcitx5.android.input.TouchProbeLog
import org.fcitx.fcitx5.android.input.swipe.SWIPE_BASE_SLOP_DP
import org.fcitx.fcitx5.android.input.swipe.SwipeDirection
import org.fcitx.fcitx5.android.input.swipe.flyTextSensitivityScale
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.padding
import splitties.views.textAppearance

class InputMethodTestFragment : Fragment() {

    private lateinit var keyCodeText: TextView
    private lateinit var modifiersText: TextView
    private lateinit var actionText: TextView
    private lateinit var charText: TextView
    private lateinit var logText: TextView
    private lateinit var imeLogText: TextView
    private lateinit var touchCoordText: TextView
    private lateinit var touchInfoText: TextView
    private lateinit var touchCountsText: TextView
    private lateinit var touchGestureText: TextView
    private lateinit var touchLogText: TextView
    private lateinit var touchTrailView: TouchTrailView

    private val logBuilder = StringBuilder()
    private var keyEventCount = 0

    /** Stable references so the probe listeners can be unregistered again in onPause. */
    private val onProbeChanged: () -> Unit = { renderImeLog() }

    private var lastTouchDetailAt = 0L

    /**
     * Fires on every sample, which these surfaces emit at up to ~125Hz. The canvas is left to vsync
     * (`postInvalidateOnAnimation`) and the text detail is throttled: rebuilding a dozen formatted
     * lines per sample buys nothing a human can read, and the counters are cheap enough to keep live.
     */
    private val onTouchProbeChanged: () -> Unit = {
        renderTouchHead()
        if (::touchTrailView.isInitialized) touchTrailView.postInvalidateOnAnimation()
        val now = SystemClock.uptimeMillis()
        if (now - lastTouchDetailAt >= DETAIL_THROTTLE_MS) {
            lastTouchDetailAt = now
            renderTouchDetail()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Main input area with hint. Deliberately small: it holds one line of text, and the
            // panels below are what the page is for — at 1080x1200 (576x640dp) the panels' own
            // content runs to ~1150px, so every pixel given to empty space up here is a pixel of
            // scrolling added down there.
            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    0.5f
                )
                isFillViewport = true
                addView(TextView(context).apply {
                    setText(R.string.test_input_hint)
                    padding = dp(16)
                    textAppearance = android.R.style.TextAppearance_Material_Body1
                })
            }
            addView(scrollView)

            // IME-side probe panel. The panel below listens on this window's view tree, so it only
            // ever sees the keys the app itself receives — system keys such as home / recents are
            // consumed by the framework before *any* window, so "the binding does nothing" can never
            // be diagnosed from there. This panel reports what the input method service receives,
            // which is where the shortcut matching actually runs (see KeyProbeLog).
            val imePanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(0xFFEFEFEF.toInt())
                padding = dp(12)

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(context).apply {
                        setText(R.string.key_probe_ime_log)
                        textAppearance = android.R.style.TextAppearance_Material_Medium
                        setTextColor(0xFF333333.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    })
                    addView(Button(context).apply {
                        setText(R.string.key_probe_clear)
                        isAllCaps = false
                        minWidth = 0
                        minimumWidth = 0
                        setOnClickListener { KeyProbeLog.clear() }
                    })
                })

                addView(TextView(context).apply {
                    setText(R.string.key_probe_ime_hint)
                    textAppearance = android.R.style.TextAppearance_Material_Caption
                    setTextColor(0xFF666666.toInt())
                    setPadding(0, dp(4), 0, dp(6))
                })

                // Focusing this field is what makes the IME visible — and an input method that is not
                // shown never has its onKeyDown called, so nothing would be recorded without it.
                addView(EditText(context).apply {
                    hint = getString(R.string.key_probe_ime_edit_hint)
                    inputType = EditorInfo.TYPE_CLASS_TEXT or
                            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setSingleLine()
                })

                imeLogText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Caption
                    setTextColor(0xFF444444.toInt())
                    setText(R.string.key_probe_waiting)
                }
                addView(ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(96)
                    )
                    addView(imeLogText)
                })
            }

            // Keyboard touch-surface probe: answers "can we even see coordinates from it?".
            // Each path that can deliver those coordinates is counted separately, because a touch
            // that lands on the wrong window looks exactly like no touch at all.
            val touchPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(0xFFE8EEF5.toInt())
                padding = dp(12)

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(context).apply {
                        setText(R.string.touch_probe_title)
                        textAppearance = android.R.style.TextAppearance_Material_Medium
                        setTextColor(0xFF333333.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    })
                    addView(Button(context).apply {
                        setText(R.string.key_probe_clear)
                        isAllCaps = false
                        minWidth = 0
                        minimumWidth = 0
                        setOnClickListener { TouchProbeLog.clear() }
                    })
                })

                // No capture switch any more: the keyboard surface's samples arrive on the IME
                // window's own generic-motion channel (FcitxInputMethodService
                // .installDecorMotionListener), which needs no window claim and no mask, and is
                // recorded whenever this page is in the foreground (TouchProbeLog.recording).

                // Canvas on the left, read-outs on the right. The display is portrait while this panel
                // is wide, so a full-width canvas would spend most of its box on letterboxing — see
                // TouchTrailView.onMeasure, which sizes the canvas to the display's own ratio.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL

                    touchTrailView = TouchTrailView(context).apply {
                        // No size hints: TouchTrailView.onMeasure derives both dimensions from the
                        // display's aspect ratio (see the note there on why a layout weight is wrong).
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    addView(touchTrailView)

                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        setPadding(dp(10), 0, 0, 0)

                        touchCoordText = TextView(context).apply {
                            textAppearance = android.R.style.TextAppearance_Material_Subhead
                            setTextColor(0xFF1B3A5C.toInt())
                            setText(R.string.touch_probe_waiting)
                        }
                        addView(touchCoordText)

                        touchInfoText = TextView(context).apply {
                            textAppearance = android.R.style.TextAppearance_Material_Body2
                            setTextColor(0xFF666666.toInt())
                            setPadding(0, dp(3), 0, 0)
                        }
                        addView(touchInfoText)

                        touchCountsText = TextView(context).apply {
                            textAppearance = android.R.style.TextAppearance_Material_Caption
                            setTextColor(0xFF666666.toInt())
                            setPadding(0, dp(5), 0, 0)
                        }
                        addView(touchCountsText)
                    })
                })

                // Gesture verdict — the one question the canvas cannot answer by itself: "would the
                // keyboard have acted on that swipe?". Judged with the SHIPPED thresholds and axis
                // rule (see TouchGestures), so a swipe that reads "below threshold" here is a swipe
                // the fly-text feature would also have ignored.
                touchGestureText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Body2
                    setTextColor(0xFF1B3A5C.toInt())
                    setPadding(0, dp(6), 0, 0)
                    setText(R.string.touch_gesture_waiting)
                }
                addView(touchGestureText)

                addView(TextView(context).apply {
                    setText(R.string.touch_probe_hint)
                    textAppearance = android.R.style.TextAppearance_Material_Caption
                    setTextColor(0xFF777777.toInt())
                    setPadding(0, dp(4), 0, dp(4))
                })

                touchLogText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Caption
                    setTextColor(0xFF444444.toInt())
                    setHorizontallyScrolling(true)
                }
                addView(ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(64)
                    )
                    addView(touchLogText)
                })
            }

            // Bottom KeyCode display panel
            val bottomPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(0xFFF5F5F5.toInt())
                padding = dp(12)

                keyCodeText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Subhead
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(keyCodeText)

                modifiersText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Body2
                    setTextColor(0xFF666666.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(modifiersText)

                actionText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Body2
                    setTextColor(0xFF666666.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(actionText)

                charText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Body2
                    setTextColor(0xFF666666.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(charText)

                // Divider
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(0xFFCCCCCC.toInt())
                })

                TextView(context).apply {
                    setText(R.string.key_event_log)
                    padding = dp(8)
                    textAppearance = android.R.style.TextAppearance_Material_Medium
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }.also { addView(it) }

                logText = TextView(context).apply {
                    textAppearance = android.R.style.TextAppearance_Material_Caption
                    setTextColor(0xFF666666.toInt())
                    setHorizontallyScrolling(true)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(logText)
            }

            // The three panels share one scrollable column: on a 1080x1200 (576x640dp) screen they
            // cannot all fit beside the test area, and clipping the newest samples would defeat the
            // point of a probe.
            addView(ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    2.5f
                )
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(imePanel)
                    addView(touchPanel)
                    addView(bottomPanel)
                })
            })

            // Set up key listener on the root view
            isFocusableInTouchMode = true
            requestFocus()
            setOnKeyListener { _, keyCode, event ->
                handleKeyEvent(keyCode, event)
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateDisplay(0, null, null, null)

        // Reserve room for the IME. This activity is edge-to-edge and never resizes for the IME, so
        // the input method's floating bar (a separate window) would otherwise sit on top of the
        // bottom-most panel rows and hide them. Measured on the Titan: mImeHeight=75,
        // InsetsSource type=ime frame=[0,1125][1076,1200] while `mImeInsetsConsumed=false`, i.e. the
        // framework reports the real 75px bar — nothing here needs a magic constant. Take the larger
        // of the two so the navigation bar still wins while the IME is hidden.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(bottom = maxOf(navBars.bottom, ime.bottom))
            // Hand the IME's top edge to the canvas so it can show the two regions side by side —
            // on this hardware they do not overlap, which is the whole reason a gesture on the
            // keyboard surface never reaches the input method.
            if (::touchTrailView.isInitialized) {
                touchTrailView.imeTop = if (ime.bottom > 0) {
                    resources.displayMetrics.heightPixels - ime.bottom.toFloat()
                } else {
                    -1f
                }
            }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        // Probes only while this page is on screen: they are diagnostics, not a permanent tap.
        KeyProbeLog.addListener(onProbeChanged)
        KeyProbeLog.recording = true
        TouchProbeLog.addListener(onTouchProbeChanged)
        TouchProbeLog.recording = true
        renderImeLog()
        renderTouch()
    }

    override fun onPause() {
        // Unregister first: turning recording off clears the backlog and notifies listeners.
        KeyProbeLog.removeListener(onProbeChanged)
        KeyProbeLog.recording = false
        TouchProbeLog.removeListener(onTouchProbeChanged)
        TouchProbeLog.recording = false
        super.onPause()
    }

    private fun renderTouch() {
        renderTouchHead()
        renderTouchDetail()
        if (::touchTrailView.isInitialized) touchTrailView.invalidate()
    }

    /** Cheap read-outs — safe to run on every sample. */
    private fun renderTouchHead() {
        if (!isAdded || !::touchCoordText.isInitialized) return
        val latest = TouchProbeLog.snapshot().firstOrNull()

        touchCoordText.text = if (latest == null) {
            getString(R.string.touch_probe_waiting)
        } else {
            "X = ${"%.1f".format(latest.x)}    Y = ${"%.1f".format(latest.y)}"
        }
        touchInfoText.text = latest?.let {
            "${TouchProbeLog.Entry.actionName(it.action)} · " +
                    "${TouchProbeLog.Entry.sourceName(it.source)}" +
                    " · dev=${it.deviceId} · ${it.path}"
        } ?: ""
        touchCountsText.text = getString(
            R.string.touch_probe_counts,
            TouchProbeLog.counts().entries
                .joinToString("  ") { "${it.key}=${it.value}" }
                .ifEmpty { "-" }
        )
        renderGesture()
    }

    /**
     * Gesture verdict for the newest stroke, plus the keyboard-surface tally.
     *
     * Runs on every sample, like the rest of the head read-out: a swipe has to be judged while the
     * finger is still moving, and walking the (800-sample-capped) backlog costs nothing beside
     * drawing the canvas, which rebuilds the same strokes on every frame.
     */
    private fun renderGesture() {
        if (!isAdded || !::touchGestureText.isInitialized) return
        // Scale density by the fly-text sensitivity pref so the read-out judges strokes with the
        // very same effective thresholds the keyboard uses right now (single mapping in
        // SwipeGeometry.flyTextSensitivityScale — the selector passes the identical product).
        val density = resources.displayMetrics.density * flyTextSensitivityScale(
            AppPrefs.getInstance().hardwareKeyboard.keyboardFlyTextSensitivity.getValue()
        )
        val report = buildGestureReport(TouchProbeLog.snapshot(), density)

        val latest = report.latest
        if (latest == null) {
            touchGestureText.text = getString(R.string.touch_gesture_waiting)
            return
        }
        val head = getString(
            R.string.touch_gesture_line,
            directionLabel(latest.direction, latest.travelPx, density),
            sourceLabel(latest.keyboardSurface, latest.deviceId),
            travelLabel(latest.travelPx, density),
            latest.durationMs,
            "${latest.peakPxPerSec.toInt()}px/s",
            latest.samples
        )
        val counts = report.counts
        touchGestureText.text = head + "\n" + getString(
            R.string.touch_gesture_counts,
            counts.up, counts.down, counts.left, counts.right, counts.belowThreshold
        )
    }

    /**
     * The verdict in the terms the feature acts on — the same mapping fly-text uses, including the
     * "swap page swipe direction" preference, so the panel never claims a page direction the keyboard
     * would not actually take.
     */
    private fun directionLabel(
        direction: SwipeDirection?,
        travelPx: Float,
        density: Float,
    ): String = when (direction) {
        SwipeDirection.UP -> dirAction(R.string.touch_gesture_dir_up, R.string.touch_gesture_action_pick)
        SwipeDirection.DOWN ->
            dirAction(R.string.touch_gesture_dir_down, R.string.touch_gesture_action_ignore)

        SwipeDirection.LEFT, SwipeDirection.RIGHT -> {
            // Default: left = next page, right = previous. The swap toggle mirrors it.
            val swapped = AppPrefs.getInstance().hardwareKeyboard.keyboardFlyTextSwapPage.getValue()
            val next = (direction == SwipeDirection.LEFT) != swapped
            dirAction(
                if (direction == SwipeDirection.LEFT) R.string.touch_gesture_dir_left
                else R.string.touch_gesture_dir_right,
                if (next) R.string.touch_gesture_action_next
                else R.string.touch_gesture_action_prev
            )
        }

        // Never travelled far enough: spell out both numbers, because "how short" is the whole
        // diagnosis (the feature's threshold is invisible otherwise).
        null -> getString(
            R.string.touch_gesture_below,
            travelLabel(SWIPE_BASE_SLOP_DP * density, density),
            travelLabel(travelPx, density)
        )
    }

    private fun dirAction(directionRes: Int, actionRes: Int): String =
        getString(R.string.touch_gesture_dir_action, getString(directionRes), getString(actionRes))

    private fun sourceLabel(keyboardSurface: Boolean, deviceId: Int): String = getString(
        if (keyboardSurface) R.string.touch_gesture_src_surface else R.string.touch_gesture_src_screen,
        deviceId
    )

    private fun travelLabel(px: Float, density: Float): String =
        "${px.toInt()}px (${(px / density).toInt()}dp)"

    /** The scrolling sample list — throttled, see [onTouchProbeChanged]. */
    private fun renderTouchDetail() {
        if (!isAdded || !::touchLogText.isInitialized) return
        touchLogText.text = TouchProbeLog.snapshot().take(12).joinToString("\n") { it.toLine() }
    }

    private fun renderImeLog() {
        if (!isAdded || !::imeLogText.isInitialized) return
        val entries = KeyProbeLog.snapshot()
        imeLogText.text = if (entries.isEmpty()) {
            getString(R.string.key_probe_waiting)
        } else {
            entries.joinToString("\n") { it.toLine() }
        }
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent) {
        val keyCodeName = KeyEvent.keyCodeToString(keyCode)
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
            KeyEvent.ACTION_UP -> "ACTION_UP"
            KeyEvent.ACTION_MULTIPLE -> "ACTION_MULTIPLE"
            else -> "UNKNOWN(${event.action})"
        }

        // Build modifiers string
        val modifiers = buildModifiersString(event)

        // Get character if applicable
        val char = event.unicodeChar.let { unicode ->
            if (unicode != 0) {
                val ch = unicode.toChar()
                val hex = "U+%04X".format(unicode)
                "'$ch' ($hex)"
            } else {
                null
            }
        }

        // Update display
        updateDisplay(keyCode, keyCodeName, actionName, modifiers, char)

        // Add to log
        keyEventCount++
        val logLine = "#$keyEventCount: $keyCodeName($keyCode) $actionName${modifiers.takeIf { it.isNotEmpty() }?.let { " [$it]" } ?: ""}${char?.let { " $it" } ?: ""}"
        logBuilder.insert(0, logLine + "\n")
        if (logBuilder.length > 2000) {
            logBuilder.delete(2000, logBuilder.length)
        }
        logText.text = logBuilder.toString()
    }

    private fun buildModifiersString(event: KeyEvent): String {
        val mods = mutableListOf<String>()
        if (event.isShiftPressed) mods.add("SHIFT")
        if (event.isCtrlPressed) mods.add("CTRL")
        if (event.isAltPressed) mods.add("ALT")
        if (event.isMetaPressed) mods.add("META")
        if (event.isCapsLockOn) mods.add("CAPS_LOCK")
        if (event.isNumLockOn) mods.add("NUM_LOCK")
        if (event.isScrollLockOn) mods.add("SCROLL_LOCK")
        if (event.isSymPressed) mods.add("SYM")
        if (event.isFunctionPressed) mods.add("FN")
        return mods.joinToString(" + ")
    }

    private fun updateDisplay(
        keyCode: Int,
        keyCodeName: String?,
        actionName: String?,
        modifiers: String?,
        char: String? = null
    ) {
        keyCodeText.text = if (keyCodeName != null && keyCode != 0) {
            getString(R.string.key_code_format, keyCodeName, keyCode)
        } else {
            getString(R.string.key_code_waiting)
        }

        modifiersText.text = if (!modifiers.isNullOrEmpty()) {
            getString(R.string.modifiers_format, modifiers)
        } else {
            getString(R.string.modifiers_none)
        }

        actionText.text = if (actionName != null) {
            getString(R.string.action_format, actionName)
        } else {
            getString(R.string.action_waiting)
        }

        charText.text = if (char != null) {
            getString(R.string.char_format, char)
        } else {
            getString(R.string.char_none)
        }
    }

    private companion object {
        /** Text-detail refresh interval; the canvas itself updates on vsync. */
        const val DETAIL_THROTTLE_MS = 150L
    }
}