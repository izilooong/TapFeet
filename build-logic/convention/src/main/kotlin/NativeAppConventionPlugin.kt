/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration.FilterType
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.configure

@Suppress("unused")
class NativeAppConventionPlugin : NativeBaseConventionPlugin() {

    override fun apply(target: Project) {
        super.apply(target)

        target.pluginManager.apply(target.libs.plugins.android.application.get().pluginId)

        target.extensions.configure<ApplicationExtension> {
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
            buildFeatures {
                prefab = true
            }
        }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val outputs = variant.outputs
                // different version code based on abi
                outputs.forEach { output ->
                    val abi = output.filters.find { it.filterType == FilterType.ABI }
                    if (abi != null) {
                        output.versionCode.set(
                            Versions.calculateVersionCode(abi.identifier, target.buildVersionName)
                        )
                    }
                }
                // Carry the release version in the APK file name (tapfeet.ime-v1.0.13.apk) — the
                // same name the online-update download URL template expects, so the built file can
                // be uploaded without a manual rename. Only when the build produces exactly one
                // output (the single-ABI flow, buildABI override): a multi-ABI build keeps the
                // default per-ABI names so the outputs cannot collide on disk.
                if (outputs.size == 1) {
                    val baseName = target.extensions
                        .getByType(BasePluginExtension::class.java).archivesName.get()
                    outputs.single().outputFileName.set(
                        "$baseName-${target.buildVersionName}.apk"
                    )
                }
            }
        }
    }

}
