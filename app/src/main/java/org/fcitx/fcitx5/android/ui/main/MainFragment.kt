/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.update.UpdateChecker
import org.fcitx.fcitx5.android.update.UpdateInfo
import org.fcitx.fcitx5.android.update.UpdateManager
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.setup
import timber.log.Timber

class MainFragment : PaddingPreferenceFragment() {

    // "Check for updates" preference; its summary reflects the cached / last-check state.
    private var checkUpdatePref: Preference? = null

    // Set when we jumped to settings to grant the "install unknown apps" permission;
    // cleared once the install is (re)triggered or the user denies it.
    private var awaitingInstallPermission = false

    override fun onStart() {
        super.onStart()
        // Show any cached result immediately, then refresh in the background (throttled).
        checkUpdatePref?.let { refreshSummaryFromCache(it) }
        maybeAutoCheck()
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute
    ) {
        addPreference(title, icon = icon) {
            navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("Fcitx") {
                addDestinationPreference(
                    R.string.global_options,
                    R.drawable.ic_baseline_tune_24,
                    SettingsRoute.GlobalConfig
                )
                addDestinationPreference(
                    R.string.input_methods,
                    R.drawable.ic_baseline_language_24,
                    SettingsRoute.InputMethodList
                )
                addDestinationPreference(
                    R.string.addons,
                    R.drawable.ic_baseline_extension_24,
                    SettingsRoute.AddonList
                )
            }
            addCategory("Android") {
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.Theme
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard
                )
                addDestinationPreference(
                    R.string.hardware_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.HardwareKeyboard
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.CandidatesWindow
                )
                addDestinationPreference(
                    R.string.candidate_bar_options,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.CandidateBar
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard,
                    SettingsRoute.Clipboard
                )
                addDestinationPreference(
                    R.string.emoji_and_symbols,
                    R.drawable.ic_baseline_emoji_symbols_24,
                    SettingsRoute.Symbol
                )
                addDestinationPreference(
                    R.string.plugins,
                    R.drawable.ic_baseline_android_24,
                    SettingsRoute.Plugin
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced
                )
                addDestinationPreference(
                    R.string.lab,
                    R.drawable.ic_baseline_science_24,
                    SettingsRoute.Lab
                )
            }
            addCategory(R.string.about) {
                isIconSpaceReserved = false
                val pref = Preference(requireContext()).apply {
                    setup(
                        title = getString(R.string.check_update),
                        icon = R.drawable.ic_baseline_sync_24
                    ) { checkForUpdates(manual = true) }
                    setSummary(R.string.update_status_unknown)
                }
                checkUpdatePref = pref
                addPreference(pref)
                addPreference(
                    R.string.about_app,
                    BuildConfig.VERSION_NAME,
                    R.drawable.ic_baseline_info_24
                ) {
                    navigateWithAnim(SettingsRoute.About)
                }
            }
        }
    }

    // ============ Online update logic ============

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService<ConnectivityManager>() ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Be optimistic when the system API can't give a definitive answer:
            // some devices/ROMs (e.g. Q25) report a null activeNetwork or null
            // capabilities even with a working connection. Only treat a *confirmed*
            // lack of INTERNET capability as "no network"; otherwise let the real
            // request decide and surface the result via the failure branch.
            val nw = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(nw) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected != false
        }
    }

    private fun lastCheckAgeMillis(): Long {
        val ts = runCatching {
            AppPrefs.getInstance().internal.lastUpdateCheckTime.getValue().toLong()
        }.getOrDefault(0L)
        return System.currentTimeMillis() - ts
    }

    /** Background refresh, at most once every 6 hours. */
    private fun maybeAutoCheck() {
        if (lastCheckAgeMillis() < 6 * 60 * 60 * 1000L) return
        if (!isNetworkAvailable()) return
        checkForUpdates(manual = false)
    }

    private fun refreshSummaryFromCache(pref: Preference) {
        val prefs = AppPrefs.getInstance().internal
        pref.summary = when {
            prefs.cachedUpdateAvailable.getValue() ->
                getString(R.string.update_status_available, prefs.cachedUpdateVersionName.getValue())
            prefs.lastUpdateCheckTime.getValue() != "0" ->
                getString(R.string.update_status_uptodate, BuildConfig.VERSION_NAME)
            else -> getString(R.string.update_status_unknown)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        val pref = checkUpdatePref ?: return
        if (manual) pref.summary = getString(R.string.update_status_checking)
        if (!isNetworkAvailable()) {
            if (manual) {
                Toast.makeText(requireContext(), R.string.network_unavailable, Toast.LENGTH_SHORT).show()
            }
            refreshSummaryFromCache(pref)
            return
        }
        lifecycleScope.launch {
            val override = AppPrefs.getInstance().internal.updateInfoUrlOverride.getValue()
            val result = UpdateChecker.fetchUpdateInfo(override)
            if (!isAdded) return@launch
            result.onSuccess { info ->
                val prefs = AppPrefs.getInstance().internal
                prefs.lastUpdateCheckTime.setValue(System.currentTimeMillis().toString())
                if (UpdateChecker.isUpdateAvailable(info)) {
                    prefs.cachedUpdateAvailable.setValue(true)
                    prefs.cachedUpdateVersionName.setValue(info.versionName)
                    prefs.cachedUpdateDownloadUrl.setValue(info.downloadUrl)
                    prefs.cachedUpdateReleaseNotes.setValue(info.releaseNotes)
                    pref.summary = getString(R.string.update_status_available, info.versionName)
                    if (manual) showUpdateDialog(info)
                } else {
                    prefs.cachedUpdateAvailable.setValue(false)
                    pref.summary = getString(R.string.update_status_uptodate, BuildConfig.VERSION_NAME)
                    if (manual) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.update_status_uptodate, BuildConfig.VERSION_NAME),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.onFailure {
                Timber.e(it, "Failed to fetch update info")
                if (manual) {
                    // Surface the concrete failure reason so connectivity problems
                    // (DNS/timeout/TLS) are distinguishable from HTTP/payload errors.
                    val reason = when {
                        it is java.net.UnknownHostException -> "域名无法解析"
                        it is java.net.SocketTimeoutException -> "连接超时"
                        it is javax.net.ssl.SSLException -> "TLS/证书错误"
                        it.message?.startsWith("HTTP ") == true -> it.message
                        else -> it.javaClass.simpleName
                    }
                    val prefix = if (it.message?.startsWith("HTTP ") == true) "检查失败" else "网络不可用"
                    Toast.makeText(requireContext(), "$prefix：$reason", Toast.LENGTH_LONG).show()
                }
                refreshSummaryFromCache(pref)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_found_title)
            .setMessage(getString(R.string.update_found_message, info.versionName, info.releaseNotes))
            .setNegativeButton(R.string.later) { _, _ -> }
            .setPositiveButton(R.string.download_update) { _, _ ->
                startDownload(info)
            }
            .show()
    }

    /**
     * Download the update APK (via OkHttp, following Gitee's foruda redirect) on a
     * background coroutine while showing a real progress bar, then launch the system
     * installer when the bytes are on disk.
     */
    private fun startDownload(info: UpdateInfo) {
        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val statusText = TextView(requireContext()).apply {
            setPadding(0, 24, 0, 0)
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 48, 72, 24)
            addView(progressBar)
            addView(statusText)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_update)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()
        Timber.d("开始下载更新 APK: ${info.downloadUrl}")
        lifecycleScope.launch {
            val result = UpdateManager.downloadApk(requireContext(), info.downloadUrl) { downloaded, total ->
                if (total > 0) {
                    val pct = (downloaded * 100 / total).toInt()
                    progressBar.progress = pct
                    statusText.text = "$pct%  (${downloaded / 1024} KB / ${total / 1024} KB)"
                } else {
                    progressBar.isIndeterminate = true
                    statusText.text = "${downloaded / 1024} KB 下载中…"
                }
            }
            if (!isAdded) { dialog.dismiss(); return@launch }
            dialog.dismiss()
            result.onSuccess { apk ->
                installDownloadedApk()
            }.onFailure {
                Timber.e(it, "APK download failed")
                val reason = when {
                    it is java.net.UnknownHostException -> "域名无法解析"
                    it is java.net.SocketTimeoutException -> "连接超时"
                    it is javax.net.ssl.SSLException -> "TLS/证书错误"
                    it.message?.startsWith("HTTP ") == true -> it.message
                    else -> it.javaClass.simpleName
                }
                Toast.makeText(requireContext(), "下载失败：$reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Install the already-downloaded APK, handling the API 26+ "install unknown apps" gate.
     * If the permission is missing we open settings and remember that we're waiting; [onResume]
     * re-triggers the install once the user grants it — so the APK is never downloaded twice.
     */
    private fun installDownloadedApk() {
        val apk = UpdateManager.apkFile(requireContext())
        if (!apk.exists()) return
        if (UpdateManager.isInstallPermissionGranted(requireContext())) {
            awaitingInstallPermission = false
            UpdateManager.startInstall(requireContext(), apk)
        } else {
            awaitingInstallPermission = true
            Toast.makeText(requireContext(), R.string.grant_install_permission, Toast.LENGTH_LONG).show()
            UpdateManager.openInstallSettings(requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingInstallPermission) {
            if (UpdateManager.isInstallPermissionGranted(requireContext())) {
                awaitingInstallPermission = false
                installDownloadedApk()
            } else {
                // Came back from settings but still denied — stop retrying and let the
                // user re-trigger from the update dialog if they change their mind.
                awaitingInstallPermission = false
                Toast.makeText(requireContext(), R.string.install_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

}
