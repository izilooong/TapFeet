/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.JavaVersion

object Versions {

    val java = JavaVersion.VERSION_11
    const val compileSdk = 36
    const val minSdk = 23
    const val targetSdk = 36

    const val defaultCMake = "3.31.6"
    const val defaultNDK = "28.0.13004108"
    const val defaultBuildTools = "36.1.0"

    // Release version now lives in docs/更新内容.txt (the last `# vX.Y.Z` section): bumping a
    // release means adding a section there, and the version code derives from the version name.
    // These two are only FALLBACKS for version names that do not parse (e.g. a raw git hash).
    const val baseVersionCode = 23
    const val baseVersionName = "0.1.2"

    val supportedABIs = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    const val fallbackABI = "arm64-v8a"

    /**
     * Derive the base version code from a version name like "v1.0.13" or "v1.0.13-01":
     * major*100000 + minor*1000 + patch*10 + build suffix. Each component stays well inside an Int
     * and the result is monotonic for every plausible version bump, so the in-app updater's
     * "newer versionCode" check keeps working without anyone hand-bumping a counter. Falls back to
     * [baseVersionCode] when [versionName] does not parse (e.g. a raw git hash from git describe).
     */
    fun baseVersionCodeFrom(versionName: String): Int {
        val m = versionCodeRegex.find(versionName) ?: return baseVersionCode
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        val patch = m.groupValues[3].toInt()
        val build = m.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return major * 100_000 + minor * 1_000 + patch * 10 + build
    }

    private val versionCodeRegex = Regex("[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+))?")

    fun calculateVersionCode(abi: String = fallbackABI, versionName: String = baseVersionName): Int {
        val abiId = when (abi) {
            "armeabi-v7a" -> 1
            "arm64-v8a" -> 2
            "x86" -> 3
            "x86_64" -> 4
            else -> 0
        }
        return baseVersionCodeFrom(versionName) * 10 + abiId
    }
}
