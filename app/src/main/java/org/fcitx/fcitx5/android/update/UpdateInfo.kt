/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.update

import kotlinx.serialization.Serializable

/**
 * Metadata describing the latest published version. Fetched as a static JSON file
 * (see [org.fcitx.fcitx5.android.utils.Const.updateInfoUrlGitee]).
 */
@Serializable
data class UpdateInfo(
    /** Monotonic version code; compared against [android.os.Build.VERSION_CODES] via BuildConfig. */
    val versionCode: Int,
    /** Human-readable version, e.g. "V1.0.3". */
    val versionName: String,
    /** Direct, HTTPS-accessible APK download URL. */
    val downloadUrl: String,
    /** Optional plain-text release notes shown in the update dialog. */
    val releaseNotes: String = "",
    /** If the running app's versionCode is below this, the update is mandatory. */
    val minVersionCode: Int = 0,
    /** ISO date of publication, for display only. */
    val publishDate: String = ""
)
