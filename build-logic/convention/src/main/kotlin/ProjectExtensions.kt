/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApkSigningConfig
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.the
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun Project.runCmd(cmd: String, defaultValue: String = ""): String {
    val output = providers.exec {
        commandLine = cmd.split(" ")
    }
    return if (output.result.get().exitValue == 0) {
        output.standardOutput.asText.get().trim()
    } else {
        defaultValue
    }
}

val Project.libs get() = the<LibrariesForLibs>()

val Project.assetsDir: File
    get() = file("src/main/assets").also { it.mkdirs() }

val Project.cleanTask: Task
    get() = tasks.getByName("clean")

val Project.cmakeVersion
    get() = ep("CMAKE_VERSION", "cmakeVersion") { Versions.defaultCMake }

val Project.ndkVersion
    get() = ep("NDK_VERSION", "ndkVersion") { Versions.defaultNDK }

val Project.buildToolsVersion
    get() = ep("BUILD_TOOLS_VERSION", "buildTools") { Versions.defaultBuildTools }

val Project.buildVersionName
    get() = epn("BUILD_VERSION_NAME", "buildVersionName")
        ?: releaseVersionFromChangelog
        ?: runCmd("git describe --tags --long --always", Versions.baseVersionName)

/**
 * The single release source of truth: the LAST `# vX.Y.Z` heading in `docs/更新内容.txt`,
 * normalized to a leading "v" (old headings like `# 更新内容 1.0.1` normalize to `v1.0.1`).
 * Bumping a release means adding a section there — versionName / versionCode / downloadUrl /
 * releaseNotes all derive from it. Null when the file is missing or has no parsable heading, in
 * which case the build falls back to git describe / [Versions.baseVersionName].
 */
val Project.releaseVersionFromChangelog: String?
    get() = runCatching { rootProject.file("docs/更新内容.txt").readText() }
        .getOrNull()
        ?.let { text -> changelogVersionRegex.findAll(text).lastOrNull()?.groupValues?.get(1) }
        ?.let { if (it.startsWith("v")) it else "v$it" }

/**
 * The body (non-empty lines) of the last section in `docs/更新内容.txt`, joined with "\n" —
 * the release notes shown by the in-app updater. Null when nothing usable is found.
 */
val Project.releaseNotesFromChangelog: String?
    get() {
        val text = runCatching { rootProject.file("docs/更新内容.txt").readText() }.getOrNull()
            ?: return null
        val last = changelogVersionRegex.findAll(text).lastOrNull() ?: return null
        return text.substring(last.range.last + 1)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }
    }

/** Matches `# 更新内容 v1.0.13`, `# v1.0.13`, `# 1.0.13-01` … and captures the version string. */
private val changelogVersionRegex =
    Regex("^#\\s*(?:更新内容\\s*)?[vV]?(\\d+(?:\\.\\d+){1,3}(?:-\\d+)?)\\s*$", RegexOption.MULTILINE)

val Project.buildCommitHash
    get() = ep("BUILD_COMMIT_HASH", "buildCommitHash") {
        runCmd("git rev-parse HEAD", "N/A")
    }

val Project.buildTimestamp
    get() = ep("BUILD_TIMESTAMP", "buildTimestamp") {
        System.currentTimeMillis().toString()
    }

val Project.buildAbiOverride: String?
    get() = epn("BUILD_ABI", "buildABI")

val Project.signKeyBase64: String?
    get() = epn("SIGN_KEY_BASE64", "signKeyBase64")

val Project.signKeyFile: String?
    get() = epn("SIGN_KEY_FILE", "signKeyFile")

private var signKeyTempFile: File? = null

val Project.signKey: File?
    get() {
        signKeyFile?.let {
            val file = File(it)
            if (file.exists()) return file
        }
        @OptIn(ExperimentalEncodingApi::class)
        signKeyBase64?.let {
            if (signKeyTempFile?.exists() == true) {
                return signKeyTempFile
            }
            val buildDir = layout.buildDirectory.asFile.get()
            buildDir.mkdirs()
            val file = File.createTempFile("sign-", ".ks", buildDir)
            try {
                file.writeBytes(Base64.decode(it))
                file.deleteOnExit()
                signKeyTempFile = file
                return file
            } catch (e: Exception) {
                println(e.localizedMessage ?: e.stackTraceToString())
                file.delete()
            }
        }
        return null
    }

val Project.signKeyPwd: String?
    get() = epn("SIGN_KEY_PWD", "signKeyPwd")

val Project.signKeyAlias: String?
    get() = epn("SIGN_KEY_ALIAS", "signKeyAlias")

fun NamedDomainObjectContainer<out ApkSigningConfig>.fromProjectEnv(project: Project): ApkSigningConfig? {
    val keyFile = project.signKey ?: return null
    val name = "release"
    return findByName(name) ?: create(name) {
        storeFile = keyFile
        storePassword = project.signKeyPwd
        keyAlias = project.signKeyAlias
        keyPassword = project.signKeyPwd
    }
}
