/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.effects

/**
 * Single source of truth for whether a candidate-pick — armed by a handshake that the *caller*
 * owns — should fire a non-Particles effect, given the current mode and the two master gates.
 *
 * Both [org.fcitx.fcitx5.android.input.effects.CommitEffectsOverlay.onCommit] (floating-pick
 * handshake) and
 * [org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.onCommitText]
 * (horizontal-bar handshake) delegate here, so the gate order and the `when(mode)` Fly/Bubble
 * dispatch live in exactly one place instead of being re-implemented in each.
 *
 * Particles is deliberately absent: it is driven by the master commit path in `onCommit`, not by a
 * pick handshake, so it is never returned here.
 */
object EffectTrigger {

    /**
     * @param mode current effect mode
     * @param enabled master effects switch
     * @param disableAnimation the "disable animation" advanced toggle
     * @param armMatched whether the caller's pick handshake matched this commit's text
     * @return the [EffectMode] to fire (Fly/Bubble) or null when nothing should fire
     */
    fun decide(
        mode: EffectMode,
        enabled: Boolean,
        disableAnimation: Boolean,
        armMatched: Boolean
    ): EffectMode? {
        if (!enabled || disableAnimation) return null
        if (!armMatched) return null
        return when (mode) {
            EffectMode.Fly -> EffectMode.Fly
            EffectMode.Bubble -> EffectMode.Bubble
            EffectMode.Particles -> null
        }
    }
}
