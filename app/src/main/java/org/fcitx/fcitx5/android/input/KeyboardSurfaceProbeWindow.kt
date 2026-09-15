/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow

/**
 * Diagnostic-only capture window for the physical keyboard's touch surface.
 *
 * In physical-keyboard mode the IME window is full-screen but declares its touchable region as a
 * thin bottom strip (`FcitxInputMethodService.onComputeInsets` → `TOUCHABLE_INSETS_VISIBLE`). The
 * keyboard surface maps to a screen band well above that strip, so its touches land in the IME's
 * NON-touchable region and — on this hardware — are not forwarded to the app window either. Every
 * probe channel therefore goes silent the moment the IME is shown, which is exactly why the Lab
 * page "shows no touch events" while the cursor sits in an input box.
 *
 * This window makes the IME side touchable over that band so the samples are observed. It records
 * to [TouchProbeLog] (the same singleton the Lab page reads) and consumes the touch — it is a
 * probe, not an input path, so it never forwards to a real view. Gated by
 * [org.fcitx.fcitx5.android.data.prefs.AppPrefs.hardwareKeyboard.captureKeyboardSurfaceTouch];
 * normal input is untouched.
 */
class KeyboardSurfaceProbeWindow(context: Context) {
    /**
     * Optional downstream consumer of captured touches. The window always records to
     * [TouchProbeLog.PATH_IME_SURFACE] (so the Lab page sees the surface samples regardless); this
     * hook lets a feature (e.g. keyboard fly-text) act on the same stream. Set to `{}` when no
     * feature wants the touches, so capture stays purely diagnostic.
     */
    var onTouch: (MotionEvent) -> Unit = {}

    private val window = PopupWindow(object : View(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent?): Boolean {
            event?.let {
                TouchProbeLog.record(TouchProbeLog.PATH_IME_SURFACE, it)
                onTouch(it)
            }
            return true
        }

        /**
         * The keyboard surface's stream does NOT always come in as a touch. While an IME is
         * showing, the device (dev=6 "touchPad") reports its samples with
         * `source=SOURCE_TOUCHPAD` (0x100008, CLASS_POSITION — not CLASS_POINTER) and absolute
         * display coordinates; atrace proved these reach this window's ViewRootImpl but are
         * routed through the generic-motion path (`ViewPostImeInputStage` never calls
         * [onTouchEvent] for them), so a touch-only override saw nothing — which read as "the
         * IME swallows keyboard-surface touches". Recording here as well closes that gap; the
         * actions are still DOWN/MOVE/UP, so downstream consumers need no changes.
         */
        override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
            event?.let {
                TouchProbeLog.record(TouchProbeLog.PATH_IME_SURFACE, it)
                onTouch(it)
            }
            return true
        }
    }).apply {
        animationStyle = 0
        // Touches outside the band belong to the app / IME bar; we only want the band itself.
        isOutsideTouchable = false
        isFocusable = false
    }

    private var showing = false

    fun show(token: View, x: Int, y: Int, w: Int, h: Int) {
        showing = true
        if (window.isShowing) {
            window.update(x, y, w, h)
        } else {
            window.width = w
            window.height = h
            window.showAtLocation(token, Gravity.TOP or Gravity.START, x, y)
        }
    }

    fun dismiss() {
        if (showing) {
            showing = false
            window.dismiss()
        }
    }
}
