/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.time.LocalDate

/** Gitee release URL layout — must match how APKs are uploaded per release tag. */
private const val RELEASE_URL_BASE = "https://gitee.com/zziloong/TapFeet/releases/download"
private const val APP_ID = "tapfeet.ime"

private fun defaultDownloadUrl(versionName: String) =
    "$RELEASE_URL_BASE/$versionName/$APP_ID-$versionName.apk"

/**
 * Registers the `generateUpdateJson` task, which refreshes the online-update metadata
 * (`update.json`, served via Gitee raw) from the single release source of truth — the LAST
 * `# vX.Y.Z` section of `docs/更新内容.txt`:
 *
 * - `versionName`  <- the section heading (unless BUILD_VERSION_NAME / buildVersionName overrides)
 * - `versionCode`  <- derived from that version name ([Versions.calculateVersionCode])
 * - `releaseNotes` <- the section body (bullet lines)
 * - `downloadUrl`  <- Gitee release template `…/download/<v>/<appId>-<v>.apk` when the version
 *   changed (kept from the existing file otherwise)
 * - `publishDate`  <- today when the version changed (kept otherwise)
 *
 * Bumping a release is therefore ONE manual step: add a `# vX.Y.Z` section to the changelog. A
 * release APK build depends on this task, so update.json refreshes automatically:
 *
 * ```
 * # 1. add "# v1.0.13" section to docs/更新内容.txt
 * ./gradlew :app:assembleRelease      # APK + update.json both pick the new version up
 * # or refresh update.json alone:
 * ./gradlew generateUpdateJson
 * ```
 *
 * Any field can also be forced from the command line, e.g.
 * `./gradlew generateUpdateJson -PupdateDownloadUrl=https://... -PupdateReleaseNotes=...`
 */
@Suppress("unused")
class UpdateJsonPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.tasks.register<UpdateJsonTask>("generateUpdateJson") {
            group = "release"
            description = "Refresh update.json from the release changelog (docs/更新内容.txt)"
            val file = target.file("update.json")
            updateJsonFile.set(file)
            outputFile.set(file)
        }
    }

    /** Mirrors org.fcitx.fcitx5.android.update.UpdateInfo on the app side. */
    @Serializable
    data class UpdateJsonData(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String = "",
        val releaseNotes: String = "",
        val minVersionCode: Int = 0,
        val publishDate: String = ""
    )

    abstract class UpdateJsonTask : DefaultTask() {

        /** The existing update.json to read preserved fields from (same file as the output). */
        @get:Internal
        abstract val updateJsonFile: RegularFileProperty

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @TaskAction
        fun execute() {
            with(project) {
                val jsonCodec = Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    // keep fields like minVersionCode=0 in the file so the schema stays complete
                    encodeDefaults = true
                }
                val file = updateJsonFile.get().asFile
                val existing = runCatching {
                    file.takeIf { it.exists() }
                        ?.let { jsonCodec.decodeFromString<UpdateJsonData>(it.readText()) }
                }.getOrNull()
                val versionName = buildVersionName
                val versionChanged = existing?.versionName != versionName
                val result = UpdateJsonData(
                    versionCode = Versions.calculateVersionCode(versionName = versionName),
                    versionName = versionName,
                    downloadUrl = epn("UPDATE_DOWNLOAD_URL", "updateDownloadUrl")
                        ?: (if (versionChanged || existing?.downloadUrl.isNullOrBlank())
                            defaultDownloadUrl(versionName)
                        else existing?.downloadUrl.orEmpty()),
                    releaseNotes = epn("UPDATE_RELEASE_NOTES", "updateReleaseNotes")
                        ?: releaseNotesFromChangelog ?: existing?.releaseNotes.orEmpty(),
                    minVersionCode = epn("UPDATE_MIN_VERSION_CODE", "updateMinVersionCode")
                        ?.toIntOrNull() ?: existing?.minVersionCode ?: 0,
                    publishDate = epn("UPDATE_PUBLISH_DATE", "updatePublishDate")
                        ?: existing?.publishDate?.takeIf { it.isNotBlank() && !versionChanged }
                        ?: LocalDate.now().toString()
                )
                file.writeText(jsonCodec.encodeToString(result) + "\n")
                println(
                    "update.json updated: versionCode=${result.versionCode}, " +
                            "versionName=${result.versionName}, publishDate=${result.publishDate}"
                )
            }
        }
    }
}
