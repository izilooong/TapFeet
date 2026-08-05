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

/**
 * Registers the `generateUpdateJson` task, which refreshes the online-update metadata
 * (`update.json`, served via Gitee raw) from the single sources of truth used by the APK build:
 *
 * - `versionCode` <- [Versions.calculateVersionCode] (baseVersionCode * 10 + ABI id of arm64-v8a)
 * - `versionName` <- [buildVersionName] (BUILD_VERSION_NAME / git describe)
 *
 * The remaining fields (`downloadUrl`, `releaseNotes`, `minVersionCode`, `publishDate`) are kept
 * from the existing file, so a typical release flow is:
 *
 * ```
 * # 1. bump Versions.baseVersionCode, 2. git tag
 * ./gradlew generateUpdateJson                       # refreshes versionCode + versionName only
 * # then edit downloadUrl / releaseNotes, commit & push
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
            description = "Refresh versionCode/versionName in update.json from the build version info"
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
                val result = UpdateJsonData(
                    versionCode = Versions.calculateVersionCode(),
                    versionName = buildVersionName,
                    downloadUrl = epn("UPDATE_DOWNLOAD_URL", "updateDownloadUrl")
                        ?: existing?.downloadUrl.orEmpty(),
                    releaseNotes = epn("UPDATE_RELEASE_NOTES", "updateReleaseNotes")
                        ?: existing?.releaseNotes.orEmpty(),
                    minVersionCode = epn("UPDATE_MIN_VERSION_CODE", "updateMinVersionCode")
                        ?.toIntOrNull() ?: existing?.minVersionCode ?: 0,
                    publishDate = epn("UPDATE_PUBLISH_DATE", "updatePublishDate")
                        ?: existing?.publishDate?.takeIf { it.isNotBlank() }
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
