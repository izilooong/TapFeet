/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import org.fcitx.fcitx5.android.BuildConfig

// Adapted from https://gist.github.com/hendrawd/01f215fd332d84793e600e7f82fc154b
object DeviceInfo {

    /**
     * Device id of the keyboard touch surface, or null when there is none. The single place the
     * detection lives, so [hasKeyboardTouchSurface] (a boolean) and [keyboardSurfaceYRange] (its
     * geometry) can never drift apart — they are two questions about the same device.
     */
    private fun keyboardTouchSurfaceDeviceId(): Int? =
        android.view.InputDevice.getDeviceIds().firstOrNull { id ->
            val s = android.view.InputDevice.getDevice(id)?.sources ?: return@firstOrNull false
            (s and android.view.InputDevice.SOURCE_TOUCHPAD) == android.view.InputDevice.SOURCE_TOUCHPAD &&
                    (s and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
        }

    /**
     * Whether this device has a "keyboard touch surface": an input device reporting BOTH the
     * keyboard and touchpad sources — the Titan 2 Elite's keyboard face (`touchPad`,
     * `KEYBOARD|TOUCHPAD`). This is the capability keyboard fly-text needs, so features gated on
     * this are effectively Titan2-Elite-only while staying future-proof for any device with the
     * same hardware trait. Source comparisons MUST use `==`, never `!= 0` (all pointer-ish sources
     * share the 0x2 class bit — see the Titan touch-model notes).
     */
    fun hasKeyboardTouchSurface(): Boolean = keyboardTouchSurfaceDeviceId() != null

    /**
     * Display-space Y band the keyboard touch surface can actually reach, or null when this device
     * has no such surface.
     *
     * The surface reports ABSOLUTE coordinates already scaled into display space: the Elite's
     * `touchPad` declares raw Y `0..599` and InputReader applies a `RawToDisplay` Y scale of 1.25,
     * so its declared Y motion range is `0..748.75` on a 1200px-tall logical frame. That
     * declaration is the only trustworthy source — a hand-written fraction of the screen height is
     * wrong by ~200px on this hardware.
     *
     * Kept as the device-truth accessor for interpreting surface coordinates (the Lab page's
     * read-outs, and any future band-aware diagnostic). The fly-text path itself no longer needs it:
     * the stream arrives through the IME window's decor view regardless of where the touches land.
     */
    fun keyboardSurfaceYRange(): IntRange? {
        val id = keyboardTouchSurfaceDeviceId() ?: return null
        val device = android.view.InputDevice.getDevice(id) ?: return null
        val range = device.getMotionRange(
            android.view.MotionEvent.AXIS_Y, android.view.InputDevice.SOURCE_TOUCHPAD
        ) ?: return null
        return range.min.toInt()..range.max.toInt()
    }

    fun get(context: Context) = buildString {
        appendLine("--------- Device Info")
        appendLine("OS Name: ${Build.DISPLAY}")
        appendLine("OS Version: ${System.getProperty("os.version")} (${Build.VERSION.INCREMENTAL})")
        appendLine("OS API Level: ${Build.VERSION.SDK_INT}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Model (product): ${Build.MODEL} (${Build.PRODUCT})")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Tags: ${Build.TAGS}")
        @Suppress("DEPRECATION") // we really want the physical display size
        val size = Point().also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                context.windowManager.defaultDisplay
            }.getRealSize(it)
        }
        appendLine("Screen Size: ${size.x} x ${size.y}")
        val metrics = context.resources.displayMetrics
        appendLine("Screen Density: ${metrics.density}")
        appendLine(
            "Screen orientation: ${
                when (context.resources.configuration.orientation) {
                    Configuration.ORIENTATION_PORTRAIT -> "Portrait"
                    Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
                    Configuration.ORIENTATION_UNDEFINED -> "Undefined"
                    else -> "Unknown"
                }
            }"
        )
        appendLine("--------- Package Info")
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("Package Name: ${pkgInfo.packageName}")
        appendLine("Version Code: ${pkgInfo.versionCodeCompat}")
        appendLine("Version Name: ${pkgInfo.versionName}")
        appendLine("--------- Build Info")
        appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
        appendLine("Build Time: ${iso8601UTCDateTime(BuildConfig.BUILD_TIME)}")
        appendLine("Build Git Hash: ${BuildConfig.BUILD_GIT_HASH}")
    }
}