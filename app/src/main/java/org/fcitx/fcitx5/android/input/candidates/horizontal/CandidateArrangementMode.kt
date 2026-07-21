/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * 候选栏的排列（视觉顺序）模式，独立于物理键盘快速选字开关。
 *
 * - [Macrohard] 巨硬：候选按居中向两侧展开排布 [4-2-1-3-5]（首选字居中，左右依次展开），
 *   与底排物理键（Shift/0/SYM/Shift）的 BlackBerry 槽位布局对应。
 * - [Linear] 普通：候选从左到右线性排布 [1-2-3-4-5]。
 *
 * 注意：此枚举只决定"显示顺序"，不决定是否启用物理键快速选字——后者由
 * [org.fcitx.fcitx5.android.data.prefs.AppPrefs.HardwareKeyboard.enableCandidateQuickPick] 控制。
 */
enum class CandidateArrangementMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Macrohard(R.string.arrangement_macrohard),
    Linear(R.string.arrangement_linear);

    fun toDisplayMode(): CandidateDisplayMode = when (this) {
        Macrohard -> CandidateDisplayMode.Macrohard
        Linear -> CandidateDisplayMode.Linear
    }

    companion object {
        /**
         * 解码持久化字符串。未知值回落到 [Macrohard]（保持向后兼容）。
         * 同时兼容旧值 "linear"（小写，来自早期 hardwareKeyboard.candidateDisplayMode）
         * 与新值 "Linear"（枚举名，来自本设置的 enumList）。
         */
        fun fromStorage(value: String?): CandidateArrangementMode = when (value) {
            CandidateDisplayMode.LINEAR, "Linear" -> Linear
            else -> Macrohard
        }
    }
}
