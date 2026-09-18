plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lightweight.snake"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lightweight.snake"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures { buildConfig = false }

    lint {
        // Run lint explicitly via `./gradlew lint`; don't re-run it during the
        // release build (keeps bundleRelease/assembleRelease green in CI).
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // PURE KOTLIN — no third-party dependencies allowed (except test libs below).
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}
