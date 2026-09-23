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

// Release APK builds auto-refresh update.json from docs/更新内容.txt — bumping a release means
// adding a "# vX.Y.Z" section to that file and running assembleRelease, nothing else.
project(":app").tasks.configureEach {
    if (name == "assembleRelease") dependsOn(":generateUpdateJson")
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
