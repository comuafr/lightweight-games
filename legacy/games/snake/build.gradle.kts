// Root build file. Only the Android Gradle Plugin and the Kotlin plugin are
// declared here — these are BUILD tooling, not app dependencies. The app's
// runtime dependency list (app/build.gradle.kts) stays empty: pure Kotlin only.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
