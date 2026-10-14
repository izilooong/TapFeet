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
import kotlin.math.sqrt

/**
 * 本地语音输入会话控制：
 * 录音（16kHz 单声道 PCM）→ silero-vad 静音断句 → SenseVoice 整段识别。
 *
 * **准流式上屏**：每识别完一段（说话中一个停顿）就把**累积文本**通过 [onPartialText] 发出，
 * 调用方放进编辑器 composing 区 —— 边说边长，用户能看到字在涨；整个会话结束（手动停止 /
 * 静音自动结束）才通过 [onSessionEnd] 交最终文本一次性上屏，空串表示放弃（取消录入）。
 * SenseVoice 是整段模型，这是它能做到的最接近流式的体验。
 *
 * [onAudioLevel] 每 0.1s 发一次当前音量（0..1），驱动录音动画。
 * 所有回调均在主线程发出。
 */
class VoiceInputController(
    context: Context,
    private val onPartialText: (String) -> Unit,
    private val onSessionEnd: (String) -> Unit,
    private val onStateChanged: (State) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class State {
        Idle, Recording, Recognizing
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 识别按提交顺序串行执行，保证多段语音的累积次序
    private val recognizeDispatcher = Dispatchers.Default.limitedParallelism(1)

    var state: State = State.Idle
        private set

    @Volatile
    private var recording = false

    /** 本会话累积识别文本（只在 recognizeDispatcher / stop 收尾时写读）。 */
    @Volatile
    private var sessionText = ""

    /**
     * 会话代际：start/destroy 递增。stop 的收尾协程在上屏/改状态前核对自己那代，
     * 防止「切字段后旧会话的识别结果迟到上屏/把状态打回 Idle」的竞态（文字串输入框）。
     */
    @Volatile
    private var sessionId = 0

    // 录音循环复用的转换缓冲与前文缓冲：每 0.1s 的音频不再各 new 一个数组（低端机 GC 抖动）
    private val chunkFloats = FloatArray(SAMPLE_RATE / 10)
    private val prerollBuf = FloatArray(PRE_ROLL_SAMPLES)
    private var prerollLen = 0

    /** 会话结束 60s 无新会话就释放识别器（约 300MB 内存），避免常驻拖累整机流畅度。 */
    private val releaseRecognizerRunnable = Runnable {
        scope.launch { VoiceRecognizer.release() }
    }

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
        sessionText = ""
        sessionId += 1
        prerollLen = 0
        mainHandler.removeCallbacks(releaseRecognizerRunnable)
        // 预热识别器：与录音并行，用户说完第一句时通常已就绪
        preload()
        captureJob = scope.launch(Dispatchers.IO) {
            captureLoop(rec, localVad)
        }
        setState(State.Recording)
    }

    /**
     * 停止录音并识别剩余语音；全部识别完后 [onSessionEnd] 发最终文本，回到 [State.Idle]。
     */
    @Synchronized
    fun stop() {
        if (state != State.Recording) return
        recording = false
        val mySession = sessionId
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
            val text = sessionText
            sessionText = ""
            if (mySession == sessionId) {
                mainHandler.post { onSessionEnd(text) }
                setState(State.Idle)
                // 空闲释放：60s 内没有新会话就把 ~300MB 的识别器放掉
                mainHandler.removeCallbacks(releaseRecognizerRunnable)
                mainHandler.postDelayed(releaseRecognizerRunnable, RECOGNIZER_IDLE_RELEASE_MS)
            }
            // 过期会话（已被 destroy 顶替）：什么都别做，避免迟到上屏/状态回退
        }
    }

    /**
     * 立即终止会话（密码框、输入服务销毁等场景）：丢弃未识别内容，
     * [onSessionEnd] 发空串让调用方清掉 composing 区。
     */
    @Synchronized
    fun destroy() {
        recording = false
        sessionId += 1 // 顶掉在途的 stop 收尾协程
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
        prerollLen = 0
        sessionText = ""
        // 识别器释放放到后台：若此刻有识别任务正持有它的锁，主线程等锁会卡顿
        mainHandler.removeCallbacks(releaseRecognizerRunnable)
        scope.launch { VoiceRecognizer.release() }
        mainHandler.post { onSessionEnd("") }
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
        val voiceAutoStop = AppPrefs.getInstance().voice.voiceAutoStop
        val voiceAutoStopSeconds = AppPrefs.getInstance().voice.voiceAutoStopSeconds
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
                // 就地转换到复用缓冲（不分配）
                for (i in 0 until n) chunkFloats[i] = buf[i] / 32768f
                // 音量动画：RMS 映射到 0..1
                var sum = 0f
                for (i in 0 until n) sum += chunkFloats[i] * chunkFloats[i]
                val rms = sqrt(sum / n)
                mainHandler.post { onAudioLevel((rms * 4f).coerceIn(0f, 1f)) }
                appendPreroll(chunkFloats, n)
                localVad.acceptWaveform(
                    if (n == chunkFloats.size) chunkFloats else chunkFloats.copyOf(n)
                )
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

    /** 滚动保留最近 [PRE_ROLL_SAMPLES] 个采样的前文（就地搬移，不分配）。 */
    private fun appendPreroll(samples: FloatArray, n: Int) {
        if (n >= PRE_ROLL_SAMPLES) {
            System.arraycopy(samples, n - PRE_ROLL_SAMPLES, prerollBuf, 0, PRE_ROLL_SAMPLES)
            prerollLen = PRE_ROLL_SAMPLES
            return
        }
        if (prerollLen + n > PRE_ROLL_SAMPLES) {
            val keep = PRE_ROLL_SAMPLES - n
            System.arraycopy(prerollBuf, prerollLen - keep, prerollBuf, 0, keep)
            prerollLen = keep
        }
        System.arraycopy(samples, 0, prerollBuf, prerollLen, n)
        prerollLen += n
    }

    /** 取出 VAD 已切出的完整语音段，垫上 0.5s 前文后识别，累积进会话文本并发出预览。 */
    private fun drainVad(localVad: Vad) {
        while (!localVad.empty()) {
            val segment = localVad.front()
            localVad.pop()
            val samples = segment.samples
            if (samples.size < MIN_SEGMENT_SAMPLES) continue
            val pre = if (prerollLen > 0) prerollBuf.copyOf(prerollLen) else FloatArray(0)
            val padded = pre + samples
            val job = scope.launch(recognizeDispatcher) {
                val text = VoiceRecognizer.recognize(padded)
                if (!text.isNullOrEmpty()) {
                    sessionText += text
                    val snapshot = sessionText
                    mainHandler.post { onPartialText(snapshot) }
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
        private const val RECOGNIZER_IDLE_RELEASE_MS = 60_000L
    }
}
