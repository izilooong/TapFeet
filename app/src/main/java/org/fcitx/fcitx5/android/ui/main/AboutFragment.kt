/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import android.widget.Toast
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

class AboutFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.privacyPolicyUrl)))
            }
            addPreference(
                R.string.open_source_licenses,
                R.string.licenses_of_third_party_libraries
            ) {
                navigateWithAnim(SettingsRoute.License)
            }
            addPreference(R.string.source_code, R.string.github_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.githubRepo)))
            }
            addPreference(R.string.license, Const.licenseSpdxId) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.licenseUrl)))
            }
            addCategory(R.string.version) {
                isIconSpaceReserved = false
                addPreference(R.string.current_version, Const.versionName) {
                    onAboutSecretTap()
                }
                addPreference(R.string.build_git_hash, BuildConfig.BUILD_GIT_HASH) {
                    val commit = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
                    val uri = Uri.parse("${Const.githubRepo}/commit/${commit}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_time, formatDateTime(BuildConfig.BUILD_TIME))
            }
        }
    }

    private var secretTapCount = 0
    private var lastSecretTapTime = 0L

    // Tap the "current version" row 5 times (within ~2s windows) to reveal the
    // "App display name" settings entry. Mirrors Android's developer-options unlock.
    private fun onAboutSecretTap() {
        val now = System.currentTimeMillis()
        if (now - lastSecretTapTime > 2000L) secretTapCount = 0
        lastSecretTapTime = now
        secretTapCount++
        if (secretTapCount < 5) return
        val internal = AppPrefs.getInstance().internal
        if (internal.appDisplayNameUnlocked.getValue()) return
        internal.appDisplayNameUnlocked.setValue(true)
        Toast.makeText(
            requireContext().applicationContext,
            R.string.app_display_name_unlocked_toast,
            Toast.LENGTH_LONG
        ).show()
        // Rebuild so the main settings page shows the newly unlocked entry.
        requireActivity().recreate()
    }
}
