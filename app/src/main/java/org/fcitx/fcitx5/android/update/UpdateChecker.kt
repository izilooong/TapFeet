/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.fcitx.fcitx5.android.utils.Const
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches the latest [UpdateInfo] over HTTPS and decides whether an update is available.
 * The "currently installed" version must be passed in by the caller as [currentVersionCode],
 * read at runtime from [android.content.pm.PackageManager] — NOT [android.os.BuildConfig.VERSION_CODE],
 * which is a compile-time constant that stays stale after an in-app update until the process
 * is restarted (the running settings process keeps reporting the OLD version, so an update would
 * look perpetually available).
 */
object UpdateChecker {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Plain direct connection (no system proxy). The app targets a mainland-China audience
    // and reaches Gitee directly, so we don't honor the Wi-Fi/manual proxy.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Candidate URLs, all on Gitee (the mainland-China-friendly primary source).
     * Gitee's default branch can be "master" or "main"; try both so a wrong guess doesn't 404.
     * The first URL that returns a successful response wins.
     */
    private fun candidateUrls(): List<String> {
        val gitee = Const.updateInfoUrlGitee
        val candidates = mutableListOf<String>()
        candidates += gitee
        if (gitee.contains("/raw/main/")) {
            candidates += gitee.replace("/raw/main/", "/raw/master/")
        } else if (gitee.contains("/raw/master/")) {
            candidates += gitee.replace("/raw/master/", "/raw/main/")
        }
        return candidates
    }

    /**
     * Fetch update metadata on an IO dispatcher.
     * When [overrideUrl] is provided (advanced setting), only that URL is tried; otherwise the
     * ordered [candidateUrls] are tried in turn. Returns a [Result] so callers can branch on
     * success/failure without try/catch boilerplate.
     */
    suspend fun fetchUpdateInfo(overrideUrl: String? = null): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val urls = if (!overrideUrl.isNullOrBlank()) listOf(overrideUrl.trim()) else candidateUrls()
        var lastError: Throwable? = null
        for (url in urls) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    json.decodeFromString<UpdateInfo>(body)
                }
            }.onSuccess { return@withContext Result.success(it) }
                .onFailure { lastError = it }
        }
        Result.failure(lastError ?: IOException("All update sources failed"))
    }

    fun isUpdateAvailable(info: UpdateInfo, currentVersionCode: Long): Boolean =
        info.versionCode > currentVersionCode

    fun isForceUpdate(info: UpdateInfo, currentVersionCode: Long): Boolean =
        info.minVersionCode > currentVersionCode
}
