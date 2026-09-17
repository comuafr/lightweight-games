// Root build script. It applies nothing itself, but declares every plugin
// used by sibling modules with `apply false` so the plugin classes are loaded
// ONCE in the root classloader. Without this, `:core` (KMP) and `:androidApp`
// (kotlin-android) each load their own copy of the Kotlin Gradle plugin, and
// Kotlin/Native's shared build service then fails at configuration time with
// a classloader conflict ("Cannot set the value of task ':core:compileKotlinIosArm64'
// property 'kotlinNativeBundleBuildService'...").
//
// The Android plugins are only resolved (downloaded), never applied, here;
// `./gradlew :core:jvmTest` therefore still works without an Android SDK.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}
