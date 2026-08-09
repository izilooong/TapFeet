/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.audioManager
import org.fcitx.fcitx5.android.utils.getSystemSettings
import org.fcitx.fcitx5.android.utils.vibrator
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object InputFeedbacks {

    enum class InputFeedbackMode(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowingSystem(R.string.following_system_settings),
        Enabled(R.string.enabled),
        Disabled(R.string.disabled);
    }

    private var systemSoundEffects = false
    private var systemHapticFeedback = false

    fun syncSystemPrefs() {
        systemSoundEffects = getSystemSettings<Int>(Settings.System.SOUND_EFFECTS_ENABLED) == 1
        // it says "Replaced by using android.os.VibrationAttributes.USAGE_TOUCH"
        // but gives no clue about how to use it, and this one still works
        @Suppress("DEPRECATION")
        systemHapticFeedback = getSystemSettings<Int>(Settings.System.HAPTIC_FEEDBACK_ENABLED) == 1
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val soundOnKeyPress by keyboardPrefs.soundOnKeyPress
    private val soundOnKeyPressVolume by keyboardPrefs.soundOnKeyPressVolume
    private val hapticOnKeyPress by keyboardPrefs.hapticOnKeyPress
    private val hapticOnKeyUp by keyboardPrefs.hapticOnKeyUp
    private val buttonPressVibrationMilliseconds by keyboardPrefs.buttonPressVibrationMilliseconds
    private val buttonLongPressVibrationMilliseconds by keyboardPrefs.buttonLongPressVibrationMilliseconds
    private val buttonPressVibrationAmplitude by keyboardPrefs.buttonPressVibrationAmplitude
    private val buttonLongPressVibrationAmplitude by keyboardPrefs.buttonLongPressVibrationAmplitude

    private val vibrator = appContext.vibrator

    private val hasAmplitudeControl =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) && vibrator.hasAmplitudeControl()

    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false) {
        when (hapticOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemHapticFeedback) return
        }
        if (keyUp && !hapticOnKeyUp) return
        val duration: Long
        val amplitude: Int
        val hfc: Int
        if (longPress) {
            duration = buttonLongPressVibrationMilliseconds.toLong()
            amplitude = buttonLongPressVibrationAmplitude
            hfc = HapticFeedbackConstants.LONG_PRESS
        } else {
            duration = buttonPressVibrationMilliseconds.toLong()
            amplitude = buttonPressVibrationAmplitude
            hfc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && keyUp) {
                HapticFeedbackConstants.KEYBOARD_RELEASE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }

        // there is `VibrationEffect.DEFAULT_AMPLITUDE` but no default duration;
        // also `VibrationEffect.createOneShot()` only accepts positive duration.
        // so changing amplitude without changing duration makes no sense
        if (duration != 0L) {
            // on Android 13, if system haptic feedback was disabled, `vibrator.vibrate()` won't work
            // but `view.performHapticFeedback()` with `FLAG_IGNORE_GLOBAL_SETTING` still works
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ve = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(ve)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (hapticOnKeyPress == InputFeedbackMode.Enabled) {
                // it says "Starting TIRAMISU only privileged apps can ignore user settings for touch feedback"
                // but we still seem to be able to use `FLAG_IGNORE_GLOBAL_SETTING`
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            view.performHapticFeedback(hfc, flags)
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    private val audioManager = appContext.audioManager

    // ---- Precise-volume playback -----------------------------------------------------------
    //
    // `AudioManager.playSoundEffect(fx, volume)` hands the request to the system AudioService,
    // whose SoundPool is bound to STREAM_SYSTEM. The volume we pass is only a relative scalar on
    // top of the system stream volume, and a fair number of vendor ROMs drop it entirely — which
    // makes an in-app volume slider look completely dead.
    //
    // Load the very same system keypress samples into our own SoundPool instead, so the requested
    // gain is applied verbatim. Anything that fails (missing files, unreadable, load error) falls
    // back to the AudioManager path, so behaviour degrades to the platform default rather than
    // going silent.

    private const val SystemUiSoundDir = "/system/media/audio/ui"

    private val sampleFileNames = mapOf(
        SoundEffect.Standard to "KeypressStandard.ogg",
        SoundEffect.SpaceBar to "KeypressSpacebar.ogg",
        SoundEffect.Delete to "KeypressDelete.ogg",
        SoundEffect.Return to "KeypressReturn.ogg"
    )

    // Only ever touched from the IME main thread (onCreate + key/touch handling).
    private val sampleIds = mutableMapOf<SoundEffect, Int>()

    // Written from the SoundPool load callback (binder thread), read while typing (main thread).
    private val loadedSamples: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var samplesRequested = false

    private val soundPool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) loadedSamples.add(sampleId)
                }
            }
    }

    /**
     * Load the system keypress samples into our own [SoundPool]. Idempotent — only the first call
     * does any work. Call it early (IME `onCreate`) so the very first keypress already has a
     * loaded sample instead of falling back to the platform path.
     */
    fun preloadSoundEffects() {
        // Unsynchronised fast path: this runs on every keypress via [playAtExactVolume].
        if (samplesRequested) return
        loadSamples()
    }

    @Synchronized
    private fun loadSamples() {
        if (samplesRequested) return
        samplesRequested = true
        sampleFileNames.forEach { (effect, name) ->
            val file = File(SystemUiSoundDir, name)
            if (!file.canRead()) return@forEach
            val id = runCatching { soundPool.load(file.absolutePath, 1) }.getOrNull() ?: return@forEach
            // load() returns 0 on failure.
            if (id != 0) sampleIds[effect] = id
        }
    }

    /** @return true when the sample was actually played at the requested [gain]. */
    private fun playAtExactVolume(effect: SoundEffect, gain: Float): Boolean {
        preloadSoundEffects()
        val id = sampleIds[effect] ?: return false
        // Still decoding: let this press fall back rather than dropping it silently.
        if (id !in loadedSamples) return false
        return soundPool.play(id, gain, gain, 1, 0, 1f) != 0
    }

    /**
     * Play a keypress sound effect.
     *
     * @param volume playback volume in percent (0-100); `0` means "system default volume".
     *  Defaults to the on-screen keyboard's volume preference — callers driven by a different
     *  input source (e.g. the physical keyboard) pass their own volume here.
     */
    fun soundEffect(effect: SoundEffect, volume: Int = soundOnKeyPressVolume) {
        when (soundOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemSoundEffects) return
        }
        // An explicit volume only means something if we control the gain ourselves; `0` keeps the
        // platform's own default level.
        if (volume > 0 && playAtExactVolume(effect, volume / 100f)) return
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        if (volume == 0) {
            audioManager.playSoundEffect(fx, -1f)
        } else {
            audioManager.playSoundEffect(fx, volume / 100f)
        }
    }

}