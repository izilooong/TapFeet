/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.R
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handles APK download and launching the system package installer.
 * Designed to work on the Q25's Android version (API 23+), including the
 * "install unknown apps" gate introduced in API 26.
 *
 * Downloads go through OkHttp (not the system [android.app.DownloadManager]):
 * Gitee release attachments are served via a 302 redirect to a
 * `foruda.gitee.com` URL carrying a time-limited token, and the DownloadManager
 * frequently fails that redirect (token/Referer checks). OkHttp follows the
 * redirect and writes the bytes straight to the app-private dir.
 */
object UpdateManager {

    private const val AUTHORITY_SUFFIX = ".update.fileprovider"
    private const val DOWNLOAD_SUBDIR = "updates"
    private const val APK_NAME = "tapfeet-update.apk"

    private val client by lazy {
        OkHttpClient.Builder()
            // DNS + TLS handshake on the first Gitee hit can be slow; be generous.
            .connectTimeout(30, TimeUnit.SECONDS)
            // Socket read timeout between bytes — NOT a total transfer cap, so a slow
            // but steady download of a large APK will not be cut off here.
            .readTimeout(60, TimeUnit.SECONDS)
            // Gitee release attachments 302-redirect to a foruda.gitee.com token URL.
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun updatesDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!, DOWNLOAD_SUBDIR)

    fun apkFile(context: Context): File = File(updatesDir(context), APK_NAME)

    /**
     * Download the APK via OkHttp to the app-private external files dir.
     * Follows redirects (e.g. Gitee's foruda token link) automatically.
     * [onProgress] is invoked with (downloaded bytes, total bytes) on the IO thread;
     * a total of -1 means the server did not advertise a Content-Length.
     * Returns the downloaded [File], or a failure describing what went wrong.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = updatesDir(context).apply { if (!exists()) mkdirs() }
            val apk = apkFile(context).apply { if (exists()) delete() }
            Timber.d("UpdateManager: starting APK download from $url")
            val request = Request.Builder()
                .url(url)
                // Some hosts reject the default OkHttp UA; a browser-like UA is safest.
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) TapFeetUpdate")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} ${response.message}")
                }
                val body = response.body ?: throw IOException("empty body")
                val total = body.contentLength()
                Timber.d("UpdateManager: response ${response.code}, content-length=$total")
                apk.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
                onProgress(apk.length(), apk.length())
            }
            Timber.d("UpdateManager: APK downloaded to ${apk.absolutePath} (${apk.length()} bytes)")
            apk
        }
    }

    /** Whether the app is allowed to request package installs (API 26+ only). */
    fun isInstallPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Deep-link the user to the "install unknown apps" settings page for this app. */
    fun openInstallSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /**
     * Launch the system package installer for [apkFile].
     * If the install-permission is missing (API 26+), opens settings instead.
     */
    fun startInstall(context: Context, apkFile: File) {
        if (!apkFile.exists()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInstallPermissionGranted(context)) {
            openInstallSettings(context)
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                apkFile
            )
            Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            // Pre-N: FileProvider + ACTION_INSTALL_PACKAGE are unavailable; use a file URI.
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.fromFile(apkFile),
                    "application/vnd.android.package-archive"
                )
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        runCatching { context.startActivity(intent) }
    }
}
