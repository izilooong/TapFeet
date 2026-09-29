/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber

/**
 * SenseVoice-Small（sherpa-onnx int8）识别封装。
 * 首次识别时加载模型（约 200MB，1~3 秒），建议输入窗口关闭时调用 [release] 释放内存。
 * 所有方法线程安全；识别在调用线程上同步执行。
 */
object VoiceRecognizer {

    private const val SAMPLE_RATE = 16000

    private var recognizer: OfflineRecognizer? = null

    /** 当前加载的识别器所用语言码；null = 未加载。换语言需重建识别器。 */
    private var loadedLanguage: String? = null

    val isLoaded: Boolean
        @Synchronized get() = recognizer != null

    /**
     * 按需加载模型；模型文件缺失时返回 false。
     * 设置里的识别语言变化时会自动重建识别器（模型共用，只是换语言标记）。
     */
    @Synchronized
    fun load(): Boolean {
        val language = AppPrefs.getInstance().keyboard.voiceLanguage.getValue().code
        if (recognizer != null) {
            if (loadedLanguage == language) return true
            release()
        }
        if (!VoiceModelManager.isReady()) return false
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = VoiceModelManager.modelFile.absolutePath,
                        language = language,
                        useInverseTextNormalization = true,
                    ),
                    tokens = VoiceModelManager.tokensFile.absolutePath,
                    numThreads = 2,
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(config = config)
            loadedLanguage = language
            true
        } catch (e: Exception) {
            Timber.e(e, "failed to load SenseVoice model")
            false
        }
    }

    /**
     * 识别一段 16kHz 单声道 float PCM（幅度范围 [-1, 1]），返回带标点的文本。
     * 未加载且加载失败时返回 null。
     */
    @Synchronized
    fun recognize(samples: FloatArray): String? {
        if (!load()) return null
        if (samples.isEmpty()) return ""
        return try {
            val rec = recognizer!!
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            Timber.e(e, "SenseVoice recognition failed")
            null
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        loadedLanguage = null
    }
}
