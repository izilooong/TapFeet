/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber

/**
 * Switches the launcher icon label by enabling exactly one <activity-alias> at a time.
 * The enabled alias's android:label is the name shown under the home-screen icon.
 *
 * NOTE: ComponentName uses the manifest *namespace* (org.fcitx.fcitx5.android), which differs
 * from the runtime applicationId (tapfeet.ime). Build it from the hardcoded namespace rather than
 * context.packageName, otherwise the component won't be found.
 */
object LauncherAliasManager {

    private const val NAMESPACE = "org.fcitx.fcitx5.android"

    // Persisted key -> alias component name suffix declared in AndroidManifest.xml.
    private val ALIASES = linkedMapOf(
        "default" to "AliasDajiao",
        "tapfeet" to "AliasTapFeet",
        "happy" to "AliasHappy",
        "im" to "AliasIM",
        "bb" to "AliasBB",
        "zilong" to "AliasZilong"
    )

    // Ordered (key, string-res) pairs for the settings ListPreference. The first entry ("default")
    // resolves to @string/app_name, which the build substitutes with app_name_release/_debug.
    val OPTIONS: List<Pair<String, Int>> = listOf(
        "default" to R.string.app_name,
        "tapfeet" to R.string.app_name_alias_tapfeet,
        "happy" to R.string.app_name_alias_happy,
        "im" to R.string.app_name_alias_im,
        "bb" to R.string.app_name_alias_bb,
        "zilong" to R.string.app_name_alias_zilong
    )

    /** Enable the selected alias and disable every other one. */
    fun applyAppDisplayName(context: Context, key: String) {
        val alias = ALIASES[key] ?: ALIASES["default"]!!
        val pm = context.packageManager
        // Enable the chosen alias FIRST so the launcher never loses its icon mid-switch.
        val target = ComponentName(context, "$NAMESPACE.$alias")
        if (pm.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        // Then disable all the rest.
        for (suffix in ALIASES.values) {
            if (suffix == alias) continue
            val component = ComponentName(context, "$NAMESPACE.$suffix")
            if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
        Timber.d("Launcher display name set to key='$key' (alias=$alias)")
    }

    /** Make the enabled alias match the persisted preference. Safe to call on every launch. */
    fun syncFromPref(context: Context) {
        val key = runCatching { AppPrefs.getInstance().internal.appDisplayName.getValue() }
            .getOrNull() ?: "default"
        applyAppDisplayName(context, key)
    }
}
