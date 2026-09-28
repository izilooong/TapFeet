/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.VoiceModelManager
import org.fcitx.fcitx5.android.data.voice.VoiceRecognizer
import timber.log.Timber

/**
 * 本地语音输入会话控制：
 * 录音（16kHz 单声道 PCM）→ silero-vad 静音断句 → SenseVoice 整段识别 → 回调上屏。
 *
 * - 点「开始」后持续录音，VAD 每检测到一段完整语音（前后静音）就识别并提交，
 *   边说边停顿也会分句上屏；点「停止」后冲刷剩余语音并结束会话。
 * - 回调均在主线程发出。
 */
class VoiceInputController(
    context: Context,
    private val onTextCommit: (String) -> Unit,
    private val onStateChanged: (State) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class State {
        Idle, Recording, Recognizing
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 识别按提交顺序串行执行，保证多段语音的上屏次序
    private val recognizeDispatcher = Dispatchers.Default.limitedParallelism(1)

    var state: State = State.Idle
        private set

    @Volatile
    private var recording = false

    /**
     * 最近 0.5s 的音频（VAD 切段点之前的前文）。识别前垫在段首：
     * VAD 切得太贴边会丢句首字（sherpa-onnx#2746，官方建议前后各 pad 0.5s）。
     */
    @Volatile
    private var preroll = FloatArray(0)

    private var recorder: AudioRecord? = null
    private var vad: Vad? = null
    private var captureJob: Job? = null
    private val pendingRecognitions = mutableListOf<Job>()

    /**
     * 后台预加载识别模型（1~3 秒），避免第一次识别卡顿。模型未下载时为空操作。
     */
    fun preload() {
        if (!VoiceModelManager.isReady()) return
        scope.launch { VoiceRecognizer.load() }
    }

    /**
     * 开始录音会话。需要调用方保证已授予 RECORD_AUDIO 权限、模型已下载。
     */
    @Synchronized
    fun start() {
        if (state != State.Idle) return
        if (!VoiceModelManager.isReady()) {
            onError("voice model not ready")
            return
        }
        val rec = try {
            createRecorder()
        } catch (e: Exception) {
            Timber.e(e, "failed to create AudioRecord")
            onError(e.message ?: "AudioRecord init failed")
            return
        }
        val localVad = try {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VoiceModelManager.vadFile.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 30f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "failed to create Vad")
            rec.release()
            onError(e.message ?: "VAD init failed")
            return
        }
        recorder = rec
        vad = localVad
        recording = true
        preroll = FloatArray(0)
        // 预热识别器：与录音并行，用户说完第一句时通常已就绪
        preload()
        captureJob = scope.launch(Dispatchers.IO) {
            captureLoop(rec, localVad)
        }
        setState(State.Recording)
    }

    /**
     * 停止录音并识别剩余语音，全部上屏后回到 [State.Idle]。
     */
    @Synchronized
    fun stop() {
        if (state != State.Recording) return
        recording = false
        setState(State.Recognizing)
        scope.launch(Dispatchers.IO) {
            try {
                captureJob?.join()
            } catch (_: Exception) {
            }
            val localVad = vad
            if (localVad != null) {
                try {
                    localVad.flush()
                    drainVad(localVad)
                } catch (e: Exception) {
                    Timber.e(e, "VAD flush failed")
                }
                localVad.release()
            }
            vad = null
            recorder?.let {
                try {
                    it.stop()
                } catch (_: Exception) {
                }
                it.release()
            }
            recorder = null
            val pending = synchronized(pendingRecognitions) { pendingRecognitions.toList() }
            pending.forEach { it.join() }
            synchronized(pendingRecognitions) { pendingRecognitions.clear() }
            setState(State.Idle)
        }
    }

    /**
     * 立即终止会话（密码框、输入服务销毁等场景）：丢弃未完成的识别，释放识别器内存。
     * 不销毁作用域，之后仍可重新 [start]。
     */
    @Synchronized
    fun destroy() {
        recording = false
        captureJob?.cancel()
        captureJob = null
        vad?.let {
            runCatching { it.release() }
        }
        vad = null
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        synchronized(pendingRecognitions) { pendingRecognitions.forEach { it.cancel() } }
        synchronized(pendingRecognitions) { pendingRecognitions.clear() }
        preroll = FloatArray(0)
        VoiceRecognizer.release()
        setState(State.Idle)
    }

    private fun createRecorder(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, SAMPLE_RATE / 5 * 2)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord not initialized")
        }
        rec.startRecording()
        return rec
    }

    private fun captureLoop(rec: AudioRecord, localVad: Vad) {
        val buf = ShortArray(SAMPLE_RATE / 10) // 0.1s
        val voiceAutoStop = AppPrefs.getInstance().keyboard.voiceAutoStop
        val voiceAutoStopSeconds = AppPrefs.getInstance().keyboard.voiceAutoStopSeconds
        var elapsedMs = 0L
        // 距上次语音活动的时刻；0 = 尚未说话（没说话也在计时：开了自动结束就别让会话挂死）
        var lastVoiceMs = 0L
        try {
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    Timber.w("AudioRecord.read returned $n")
                    break
                }
                elapsedMs += n * 1000L / SAMPLE_RATE
                // 兜底：单次会话最长 2 分钟，防止录音永不停止
                if (elapsedMs >= MAX_SESSION_MS) {
                    mainHandler.post { stop() }
                    break
                }
                val samples = FloatArray(n) { buf[it] / 32768f }
                preroll = (preroll + samples).takeLast(PRE_ROLL_SAMPLES).toFloatArray()
                localVad.acceptWaveform(samples)
                if (localVad.isSpeechDetected()) {
                    lastVoiceMs = elapsedMs
                }
                drainVad(localVad)
                // 静音自动结束：距上次语音活动超过 N 秒就收尾。
                // 走与手动停止同一路径（stop 会 flush VAD），句尾没吐完的语音不会丢。
                if (voiceAutoStop.getValue() &&
                    elapsedMs - lastVoiceMs >= voiceAutoStopSeconds.getValue() * 1000L
                ) {
                    mainHandler.post { stop() }
                    break
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "capture loop failed")
            mainHandler.post { onError(e.message ?: "capture failed") }
        }
    }

    /** 取出 VAD 已切出的完整语音段，垫上 0.5s 前文后提交识别。 */
    private fun drainVad(localVad: Vad) {
        while (!localVad.empty()) {
            val segment = localVad.front()
            localVad.pop()
            val samples = segment.samples
            if (samples.size < MIN_SEGMENT_SAMPLES) continue
            val padded = preroll + samples
            val job = scope.launch(recognizeDispatcher) {
                val text = VoiceRecognizer.recognize(padded)
                if (!text.isNullOrEmpty()) {
                    mainHandler.post { onTextCommit(text) }
                }
            }
            synchronized(pendingRecognitions) { pendingRecognitions.add(job) }
        }
    }

    private fun setState(newState: State) {
        state = newState
        mainHandler.post { onStateChanged(newState) }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MIN_SEGMENT_SAMPLES = 400 // 25ms
        private const val PRE_ROLL_SAMPLES = 8000 // 0.5s
        private const val MAX_SESSION_MS = 120_000L
    }
}
