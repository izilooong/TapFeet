/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.effects

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * The two mutually exclusive flavours of "input effect" — both play when text is committed, so a
 * single [org.fcitx.fcitx5.android.data.prefs.AppPrefs.Effects.mode] toggle picks between them.
 */
enum class EffectMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Fly(R.string.effects_mode_fly),
    Particles(R.string.effects_mode_particles),
    Bubble(R.string.effects_mode_bubble)
}
