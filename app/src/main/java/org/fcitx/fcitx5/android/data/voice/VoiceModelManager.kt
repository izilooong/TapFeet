/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.io.IOUtils
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 管理本地语音输入所需的模型文件：
 *  - `model.int8.onnx` / `tokens.txt`：SenseVoice-Small（int8）
 *  - `silero_vad.onnx`：静音断句 VAD 模型
 *
 * 模型体积约 230MB，不打进 APK，首次使用语音输入时按需下载到外部私有目录。
 *
 * 下载源面向国内网络排序（本项目受众在国内，同 UpdateChecker 的 Gitee 优先思路）：
 *  1. **hf-mirror.com 散文件**（HuggingFace 国内镜像，直下无需解包）；
 *  2. **GitHub Releases tar.bz2** 兜底（hf-mirror 不可用或文件被搬走时）。
 * 每个工件按源顺序逐个尝试，单源失败自动换下一个。
 * ⚠️ 没走 Gitee：Gitee 免费仓库的 Release 单附件上限低于模型体积（约 237MB），
 * 传不上去；若日后自建下载服务，把新 URL 插到源列表最前面即可。
 */
object VoiceModelManager {

    /**
     * 国内镜像散文件（hf-mirror.com，HuggingFace 的国内代理）。
     *
     * ⚠️ 必须是 **2024-07-17 官方版** SenseVoiceSmall（int8）。不要图新换成 2025-09-09：
     * 那版是粤语偏好微调（WSYue-ASR），社区实测（sherpa-onnx#2742 / #2746）它**不输出标点**
     * 且开头丢字（「开放」→「放」），整体识别也更差；维护者原话「要粤语才选它，不然不推荐」。
     * 这里用 twmht 镜像仓库（model.int8.onnx / tokens.txt 与官方 tar.bz2 内文件逐字节同尺寸）。
     */
    private const val SENSE_VOICE_MODEL_URL =
        "https://hf-mirror.com/twmht/sherpa-onnx-sense-voice-small" +
            "/resolve/main/model.int8.onnx"

    private const val SENSE_VOICE_TOKENS_URL =
        "https://hf-mirror.com/twmht/sherpa-onnx-sense-voice-small" +
            "/resolve/main/tokens.txt"

    /** GitHub Releases 兜底：tar.bz2 包，内含子目录下的 model.int8.onnx 与 tokens.txt。 */
    private const val SENSE_VOICE_TARBALL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

    private const val VAD_URL =
        "https://hf-mirror.com/R4kSo1997/sherpa-onnx-silero-vad-v5/resolve/main/silero_vad.onnx"

    private const val VAD_FALLBACK_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    private const val PROGRESS_STEP_BYTES = 256L * 1024L

    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State()
        data object Ready : State()
        data class Error(val message: String) : State()
    }

    val modelDir = File(appContext.getExternalFilesDir(null)!!, "data/voice").also { it.mkdirs() }

    val modelFile = File(modelDir, "model.int8.onnx")
    val tokensFile = File(modelDir, "tokens.txt")
    val vadFile = File(modelDir, "silero_vad.onnx")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mutableState = MutableStateFlow<State>(
        if (isReady()) State.Ready else State.NotDownloaded
    )
    val state: StateFlow<State> get() = mutableState

    private var downloadJob: Job? = null

    // 大文件下载：不设 callTimeout，只约束单次读超时
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isReady(): Boolean {
        return modelFile.length() > 0 && tokensFile.length() > 0 && vadFile.length() > 0
    }

    /** 已下载模型文件总大小（字节），用于设置页展示。 */
    fun modelSizeBytes(): Long {
        return listOf(modelFile, tokensFile, vadFile).sumOf { if (it.exists()) it.length() else 0L }
    }

    /**
     * 删除本地模型文件（含下载中的 .part 残留）。会取消进行中的下载；
     * 取消后若下载协程迟到写回文件，下次 [isReady]/[ensureDownloaded] 会重新校准状态。
     */
    @Synchronized
    fun deleteModel() {
        downloadJob?.cancel()
        downloadJob = null
        modelDir.listFiles()?.forEach { it.deleteRecursively() }
        mutableState.value = if (isReady()) State.Ready else State.NotDownloaded
    }

    /**
     * 确保模型就绪；缺失时开始下载（幂等，重复调用不会并发下载）。
     * 进度与结果通过 [state] 发出；可选回调在主线程执行。
     */
    @Synchronized
    fun ensureDownloaded(onSuccess: (() -> Unit)? = null, onFailure: ((String) -> Unit)? = null) {
        if (isReady()) {
            mutableState.value = State.Ready
            onSuccess?.let { mainHandler.post(it) }
            return
        }
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                mutableState.value = State.Downloading(0, 0)
                downloadFirst(listOf(VAD_URL, VAD_FALLBACK_URL), vadFile) { done, total ->
                    mutableState.value = State.Downloading(done, total)
                }
                if (modelFile.length() == 0L || tokensFile.length() == 0L) {
                    // 镜像散文件优先（免解包）；失败退回 GitHub tar.bz2
                    if (!tryDownloadSenseVoiceFromMirror()) {
                        downloadSenseVoiceTarball()
                    }
                }
                if (!isReady()) throw IOException("模型文件缺失")
                if (!isActive) return@launch
                mutableState.value = State.Ready
                onSuccess?.let { mainHandler.post(it) }
            } catch (e: Exception) {
                if (!isActive) return@launch
                Timber.e(e, "voice model download failed")
                val message = e.message ?: "download failed"
                mutableState.value = State.Error(message)
                onFailure?.let { cb -> mainHandler.post { cb(message) } }
            }
        }
    }

    /**
     * 按源顺序逐个尝试下载到 [dest]；全部失败抛最后一个异常。
     * [dest] 已存在时直接跳过（断点以外的重试都从头下，文件小、逻辑简单优先）。
     */
    private fun downloadFirst(
        urls: List<String>,
        dest: File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        if (dest.length() > 0) return
        var lastError: Exception? = null
        for (url in urls) {
            try {
                downloadFile(url, dest) { done, total -> onProgress?.invoke(done, total) }
                return
            } catch (e: Exception) {
                Timber.w(e, "download failed, trying next source: $url")
                lastError = e
            }
        }
        throw lastError ?: IOException("no download source for ${dest.name}")
    }

    /** 国内镜像散文件下载 SenseVoice（model.int8.onnx + tokens.txt）。失败清理残留并返回 false。 */
    private fun tryDownloadSenseVoiceFromMirror(): Boolean {
        return try {
            downloadFirst(listOf(SENSE_VOICE_MODEL_URL), modelFile) { done, total ->
                mutableState.value = State.Downloading(done, total)
            }
            downloadFirst(listOf(SENSE_VOICE_TOKENS_URL), tokensFile)
            true
        } catch (e: Exception) {
            Timber.w(e, "sense-voice mirror download failed, falling back to tarball")
            File(modelDir, "model.int8.onnx.part").delete()
            File(modelDir, "tokens.txt.part").delete()
            modelFile.delete()
            tokensFile.delete()
            false
        }
    }

    /** GitHub 兜底：下载 tar.bz2 并抽出 SenseVoice 的 model.int8.onnx 与 tokens.txt。 */
    private fun downloadSenseVoiceTarball() {
        val partModel = File(modelDir, "model.int8.onnx.part")
        val partTokens = File(modelDir, "tokens.txt.part")
        partModel.delete()
        partTokens.delete()
        val request = Request.Builder().url(SENSE_VOICE_TARBALL_URL).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $SENSE_VOICE_TARBALL_URL")
            val body = resp.body ?: throw IOException("empty response body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            // 包体较大：用计数流驱动进度
            val counted = CountingInputStream(body.byteStream()) { done ->
                mutableState.value = State.Downloading(done, total)
            }
            BZip2CompressorInputStream(counted).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && name != null) {
                            when {
                                name.endsWith("model.int8.onnx") ->
                                    copyToFile(tar, partModel)
                                name.endsWith("tokens.txt") ->
                                    copyToFile(tar, partTokens)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
        if (partModel.length() == 0L || partTokens.length() == 0L) {
            partModel.delete()
            partTokens.delete()
            throw IOException("model archive missing model.int8.onnx / tokens.txt")
        }
        if (!partModel.renameTo(modelFile)) throw IOException("rename model.int8.onnx failed")
        if (!partTokens.renameTo(tokensFile)) throw IOException("rename tokens.txt failed")
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        if (dest.length() > 0) return
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
            val body = resp.body ?: throw IOException("empty response body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            val counted = CountingInputStream(body.byteStream()) { done -> onProgress(done, total) }
            part.outputStream().use { out -> IOUtils.copyLarge(counted, out) }
        }
        if (part.length() == 0L) {
            part.delete()
            throw IOException("downloaded empty file: $url")
        }
        if (!part.renameTo(dest)) throw IOException("rename ${dest.name} failed")
    }

    private fun copyToFile(input: java.io.InputStream, dest: File) {
        dest.outputStream().use { out: OutputStream -> IOUtils.copyLarge(input, out) }
    }

    private class CountingInputStream(
        private val delegate: java.io.InputStream,
        private val onBytes: (Long) -> Unit,
    ) : java.io.InputStream() {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) bump(1L)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bump(n.toLong())
            return n
        }

        private fun bump(n: Long) {
            count += n
            if (count - lastReport >= PROGRESS_STEP_BYTES) {
                lastReport = count
                onBytes(count)
            }
        }
    }
}
