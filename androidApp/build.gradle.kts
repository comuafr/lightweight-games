// Android application shell (M0 part b). Consumes the `core` KMP module's
// Android library target, plus (from M2 on) Play Billing -- the one
// deliberate, PLAN.md §6-mandated exception to the "no third-party runtime
// dependency" rule of PLAN.md §2/§7. See AndroidManifest.xml for the
// permissions it pulls in and androidApp/proguard-rules.pro (unchanged: the
// library ships its own consumer ProGuard/R8 rules).
//
// AGP 9.4.0's built-in Kotlin support (no separate Kotlin plugin) was tried
// first, as instructed, but its bundled compiler can't read `core`'s
// classes (compiled with Kotlin 2.4.20) and bumping its version the way AGP
// documents crashes AGP 9.4.0 itself; see gradle.properties
// (android.builtInKotlin / android.newDsl) for the full story. Falling back
// to the classic org.jetbrains.kotlin.android plugin, pinned to the same
// 2.4.20 used everywhere else, avoids both problems.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Optional real release signing (PLAN.md §9 M5): if `androidApp/keystore.properties`
// exists (gitignored; never committed), it is used to sign the release build/bundle
// for real. Its four expected keys are `storeFile` (path, relative to this file, or
// absolute), `storePassword`, `keyAlias`, `keyPassword` -- see androidApp/README.md
// for how to create one. Absent that file (the default on this dev machine and in
// CI), release falls back to the debug keystore, same as M0-M4, so
// `assembleRelease`/`bundleRelease` keep working out of the box.
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

android {
    namespace = "lg.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "fr.regisp.lightgames"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk.debugSymbolLevel = "NONE"
            // Real release signing (androidApp/keystore.properties, gitignored) when
            // present; otherwise fall back to the debug keystore so
            // `assembleRelease`/`bundleRelease` still produce an installable,
            // measurable build without any secret configured (PLAN.md §9 M5).
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // Default debug signing config; nothing else to configure.
        }
    }

    androidResources {
        localeFilters += listOf("en", "fr")
    }

    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    packaging {
        resources {
            excludes += setOf(
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.billing)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
