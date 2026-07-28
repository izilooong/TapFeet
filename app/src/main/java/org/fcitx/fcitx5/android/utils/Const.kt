/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.BuildConfig

object Const {
    const val versionName = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}"
    const val githubRepo = "https://github.com/izilooong/TapFeet"
    const val licenseSpdxId = "LGPL-2.1-or-later"
    const val licenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1"
    const val privacyPolicyUrl = "https://github.com/izilooong/TapFeet"
    const val faqUrl = "https://fcitx5-android.github.io/faq/"
    // Where the app fetches online-update metadata (a static JSON file in the repo).
    // Gitee raw is the PRIMARY update source (mainland-China friendly, CN-direct reachable).
    // Bump `versionCode` in this file (and set `downloadUrl`) when publishing a new release.
    const val updateInfoUrlGitee = "https://gitee.com/zziloong/TapFeet/raw/main/update.json"
}