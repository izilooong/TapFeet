/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.voice.VoiceModelManager
import org.fcitx.fcitx5.android.utils.setup

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private var statusPref: Preference? = null
    private var downloadPref: Preference? = null
    private var deletePref: Preference? = null

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context
        val cat = PreferenceCategory(ctx).apply {
            setTitle(R.string.voice_model_management)
            isIconSpaceReserved = false
        }
        screen.addPreference(cat)

        statusPref = Preference(ctx).apply {
            setup(ctx.getString(R.string.voice_model_status))
            isSelectable = false
        }
        cat.addPreference(statusPref!!)

        downloadPref = Preference(ctx).apply {
            setup(ctx.getString(R.string.voice_model_download), icon = R.drawable.ic_baseline_mic_24) {
                onDownloadClicked()
            }
        }
        cat.addPreference(downloadPref!!)

        deletePref = Preference(ctx).apply {
            setup(ctx.getString(R.string.voice_model_delete), icon = R.drawable.ic_baseline_delete_24) {
                onDeleteClicked()
            }
        }
        cat.addPreference(deletePref!!)

        lifecycleScope.launch {
            VoiceModelManager.state.collect { updateUi(it) }
        }
    }

    private fun updateUi(state: VoiceModelManager.State) {
        val ctx = context ?: return
        when (state) {
            VoiceModelManager.State.NotDownloaded -> {
                statusPref?.summary = ctx.getString(R.string.voice_model_not_downloaded)
                downloadPref?.isEnabled = true
                downloadPref?.summary = ""
                deletePref?.isEnabled = false
            }
            is VoiceModelManager.State.Downloading -> {
                val pct = if (state.totalBytes > 0)
                    (state.downloadedBytes * 100 / state.totalBytes).toInt() else 0
                val text = ctx.getString(R.string.voice_model_downloading, pct)
                statusPref?.summary = text
                downloadPref?.isEnabled = false
                downloadPref?.summary = text
                deletePref?.isEnabled = false
            }
            VoiceModelManager.State.Ready -> {
                statusPref?.summary =
                    ctx.getString(R.string.voice_model_ready, formatSize(VoiceModelManager.modelSizeBytes()))
                downloadPref?.isEnabled = true
                downloadPref?.summary = ctx.getString(R.string.voice_model_redownload_summary)
                deletePref?.isEnabled = true
            }
            is VoiceModelManager.State.Error -> {
                statusPref?.summary = ctx.getString(R.string.voice_model_error, state.message)
                downloadPref?.isEnabled = true
                downloadPref?.summary = ""
                deletePref?.isEnabled = false
            }
        }
    }

    private fun onDownloadClicked() {
        if (VoiceModelManager.state.value is VoiceModelManager.State.Downloading) return
        VoiceModelManager.ensureDownloaded()
    }

    private fun onDeleteClicked() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_model_delete)
            .setMessage(R.string.voice_model_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> VoiceModelManager.deleteModel() }
            .show()
    }

    private fun formatSize(bytes: Long): String {
        return if (bytes < 1024 * 1024L) "${bytes / 1024} KB"
        else "${bytes / (1024 * 1024)} MB"
    }
}
