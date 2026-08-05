plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    // Registers the `generateUpdateJson` task for the online-update metadata (update.json)
    id("org.fcitx.fcitx5.android.update-json")
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
