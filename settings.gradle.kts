pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lightweight-games"

include(":core")
include(":preview")

// androidApp and iosApp are created in later milestones (M0 part b/c);
// only wire the Gradle module in once the directory actually exists so that
// this build stays green while those parts are developed independently.
if (file("androidApp").isDirectory) {
    include(":androidApp")
}
