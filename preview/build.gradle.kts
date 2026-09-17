// Dev-only headless PNG preview tool. NEVER shipped to users: it renders
// every registered game's Scene to PNG files via Java2D so rendering can be
// eyeballed on a Linux dev box with no Android/iOS device or emulator (see
// docs/ARCHITECTURE.md). Plain `org.jetbrains.kotlin.jvm` module (not the
// `org.jetbrains.kotlin.multiplatform` plugin `:core` uses) that depends on
// `:core`'s `jvm()` target.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("lg.preview.MainKt")
    // Never ships; this tool only ever runs headless on a dev machine / CI.
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

// The `application` plugin's JavaExec task otherwise defaults to this
// module's own directory as its working directory; run it from the repo
// root instead so a relative `--out preview/out` (the default, and the
// documented example invocation) lands at <repo root>/preview/out, not
// preview/preview/out.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
