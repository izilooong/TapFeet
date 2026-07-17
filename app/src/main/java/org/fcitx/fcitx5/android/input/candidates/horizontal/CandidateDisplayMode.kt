/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

/**
 * 候选栏的显示模式（与"是否启用底排物理键快速选字"绑定）。
 *
 * - [Macrohard] 巨硬模式：候选按居中向两侧展开排布，启用底排 4 个物理键（Shift_L/0/Alt_R/Shift_R
 *   或当前 keyProfile 对应键位）做快速选字。枚举值字面来源是中文输入法圈子对 Microsoft 的谐音梗，
 *   与 keyProfile（BlackBerry / TT2）无直接关系。
 * - [Linear] 普通模式：候选从左到右线性 [1, 2, 3, 4, 5] 排列，不启用物理键快速选字，仅 Space
 *   （candidate1Key）可触发首选字。
 */
enum class CandidateDisplayMode {
    Macrohard,
    Linear;

    companion object {
        const val MACROHARD = "Macrohard"
        const val LINEAR = "linear"

        /**
         * 解码持久化字符串。未知值回落到 [Macrohard]（保持向后兼容）。
         */
        fun fromStorage(value: String?): CandidateDisplayMode = when (value) {
            LINEAR -> Linear
            else -> Macrohard
        }
    }
}
