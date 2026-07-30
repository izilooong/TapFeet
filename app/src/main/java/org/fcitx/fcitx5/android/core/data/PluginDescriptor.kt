/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.core.data.PluginDescriptor.Companion.pluginPackagePrefix

/**
 * Metadata of a plugin, at `res/xml/plugin.xml`
 */
data class PluginDescriptor(
    /**
     * Must have [pluginPackagePrefix] prefix and end with `.debug` if it's debug variant
     */
    val packageName: String,
    /**
     * For future incompatible updates
     */
    val apiVersion: String,
    /**
     * May provide gettext domain
     */
    val domain: String?,
    /**
     * Can use string resource, e.g. `@string/description`
     */
    val description: String,
    /**
     * Contains IPC service with action `${mainApplicationId}.plugin.SERVICE`. Default to `false`.
     */
    val hasService: Boolean,
    val versionName: String,
    val nativeLibraryDir: String
) {
    val name = packageName.removePrefix(pluginPackagePrefix).removeSuffix(pluginPackageSuffix)

    companion object {
        const val pluginPackagePrefix = "org.fcitx.fcitx5.android.plugin."
        const val pluginPackageSuffix = ".${BuildConfig.BUILD_TYPE}"
        const val pluginAPI = "0.1"

        /**
         * Application id used for plugin *discovery* (the MANIFEST intent).
         *
         * `lib/plugin-base` hardcodes the AboutActivity action as
         * `org.fcitx.fcitx5.android.plugin.MANIFEST` (it can't use the `mainApplicationId`
         * placeholder, since it's a library module). So discovery stays pinned to upstream
         * regardless of this app's own `applicationId` (`tapfeet.ime`). This keeps both
         * upstream-built native plugins (rime/hangul/...) and our in-tree plugins discoverable.
         */
        const val contractAppId = "org.fcitx.fcitx5.android"

        /**
         * Application id used for plugin *service* binding (the `.plugin.SERVICE` action).
         *
         * Unlike discovery, the service action comes from each plugin's own manifest via the
         * `mainApplicationId` placeholder. We flipped `mainApplicationId` to `tapfeet.ime` in the
         * build convention, so in-tree hasService plugins (e.g. clipboard-filter) expose
         * `tapfeet.ime.plugin.SERVICE` / require `tapfeet.ime.permission.PLUGIN` / bind back to
         * `tapfeet.ime.IPC` — all matching this host. Upstream hasService plugins (none in scope)
         * would still use `org.fcitx.fcitx5.android.*` and won't bind, which is acceptable.
         */
        const val serviceAppId = "tapfeet.ime"
    }
}