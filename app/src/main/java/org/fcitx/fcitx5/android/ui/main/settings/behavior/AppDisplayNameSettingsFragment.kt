/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import androidx.preference.ListPreference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.LauncherAliasManager

class AppDisplayNameSettingsFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            val internal = AppPrefs.getInstance().internal
            val current = internal.appDisplayName.getValue()

            val pref = ListPreference(context).apply {
                key = internal.appDisplayName.key
                title = getString(R.string.app_display_name)
                summary = "%s"
                entries = LauncherAliasManager.OPTIONS.map { (_, strRes) -> getString(strRes) }.toTypedArray()
                entryValues = LauncherAliasManager.OPTIONS.map { (key, _) -> key }.toTypedArray()
                setDefaultValue("default")
                value = current
                isIconSpaceReserved = false
            }
            pref.setOnPreferenceChangeListener { _, newValue ->
                val key = newValue as String
                internal.appDisplayName.setValue(key)
                LauncherAliasManager.applyAppDisplayName(requireContext(), key)
                true
            }
            addPreference(pref)
        }
    }
}
