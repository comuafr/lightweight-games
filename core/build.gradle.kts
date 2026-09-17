import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Declared with `apply false` so the plugin marker resolves (network only,
    // no Android SDK required) without configuring the Android target. It is
    // applied below, conditionally, only when an Android SDK is actually
    // available. If `com.android.kotlin.multiplatform.library` ever proves
    // troublesome, the documented fallback is the classic `com.android.library`
    // plugin (alias `libs.plugins.android.library`) combined with the KMP
    // `androidTarget()` DSL instead of the `androidLibrary { }` block used here.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// --- Android SDK detection -------------------------------------------------
//
// Another agent installs the Android SDK into $ANDROID_HOME concurrently with
// this task. It may not exist yet when this build is configured, and this
// module must still configure and run `:core:jvmTest` without it. So the
// Android library plugin (and the `androidLibrary { }` target block below)
// is only applied/used when a real SDK directory is found, either via
// `local.properties` (`sdk.dir=...`, written by Android Studio) or the
// `ANDROID_HOME` environment variable (exported by tools/env.sh).
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { stream -> load(stream) }
}
val sdkDirFromLocalProperties = localProperties.getProperty("sdk.dir")
val androidHomeEnv = System.getenv("ANDROID_HOME")
val androidSdkAvailable =
    (!sdkDirFromLocalProperties.isNullOrBlank() && file(sdkDirFromLocalProperties).isDirectory) ||
        (!androidHomeEnv.isNullOrBlank() && file(androidHomeEnv).isDirectory)

if (androidSdkAvailable) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors = true
    }

    // JVM target: used ONLY to run commonTest fast on Linux dev machines / CI
    // without a JDK-less Kotlin/Native toolchain. It is never shipped to users;
    // the Android app consumes the `androidLibrary` target and the iOS app
    // consumes the XCFramework produced from the iosArm64/iosSimulatorArm64
    // targets below.
    jvm()

    if (androidSdkAvailable) {
        // The `androidLibrary { }` DSL block is normally available as a
        // generated type-safe accessor, but Gradle only generates that
        // accessor for plugins applied unconditionally in this file's
        // `plugins { }` block. Since this plugin is applied conditionally
        // above (`apply(plugin = ...)`), the accessor does not exist here, so
        // the target's extension is configured by type instead: functionally
        // identical to `androidLibrary { ... }`.
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
            namespace = "lg.core"
            compileSdk = libs.versions.compileSdk.get().toInt()
            minSdk = libs.versions.minSdk.get().toInt()
        }
    }

    val xcfName = "GamesCore"
    val xcf = XCFramework(xcfName)

    val iosArm64Target = iosArm64()
    val iosSimulatorArm64Target = iosSimulatorArm64()

    for (target in listOf(iosArm64Target, iosSimulatorArm64Target)) {
        target.binaries.framework {
            baseName = xcfName
            isStatic = true
            xcf.add(this)
        }
        target.compilerOptions {
            // Experimental flag that trades a bit of compile-time safety for a
            // smaller Kotlin/Native binary; see PLAN.md §7 (iOS footprint) and
            // §10 (risks) — to be validated by measuring the produced
            // framework size once it can be built on macOS.
            freeCompilerArgs.add("-Xbinary=smallBinary=true")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Intentionally empty: no dependency in commonMain (see PLAN.md §2).
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
