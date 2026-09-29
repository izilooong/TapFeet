/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * SenseVoice 的识别语言。[code] 直通 sherpa-onnx 的 `language` 参数
 * （合法值 auto / zh / en / ja / ko / yue，见 sherpa-onnx offline-sense-voice-model-config.cc）。
 * 模型本身是五语种一体的，切换语言不需要换模型，只需重建识别器换注入的语言标记。
 */
enum class VoiceLanguage(val code: String, override val stringRes: Int) : ManagedPreferenceEnum {
    Auto("auto", R.string.voice_language_auto),
    Chinese("zh", R.string.voice_language_zh),
    English("en", R.string.voice_language_en),
    Japanese("ja", R.string.voice_language_ja),
    Korean("ko", R.string.voice_language_ko),
    Cantonese("yue", R.string.voice_language_yue),
}
