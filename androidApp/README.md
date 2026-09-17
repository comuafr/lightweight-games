# androidApp

The Android application shell (Kotlin + `android.view.View`/`Canvas`, no
Compose, no AppCompat/Material — see `docs/ARCHITECTURE.md` and PLAN.md §2).
It consumes the `core` KMP module's Android library target and adds Play
Billing (the one third-party runtime dependency, PLAN.md §6). This file only
covers day-to-day build/install/test commands; see PLAN.md for the product
plan and `docs/ARCHITECTURE.md` for the `core` contract.

## Build

```sh
source tools/env.sh                        # pins JAVA_HOME (JDK 21) and exports ANDROID_HOME
./gradlew :androidApp:assembleDebug         # debug APK, debug-signed
./gradlew :androidApp:assembleRelease       # release APK, minified + shrunk (see "Signing" below)
./gradlew :androidApp:bundleRelease         # release AAB (Play Store upload artifact)
```

Outputs land under `androidApp/build/outputs/apk/...` and
`androidApp/build/outputs/bundle/...`.

## Install on a device/emulator

```sh
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# or, for a release build:
adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```

`-r` reinstalls over an existing install (keeps app data); drop it for a
clean install.

## Tests

```sh
./gradlew :core:jvmTest                    # core rules/fuzz/determinism tests (JVM, fast, no device)
./gradlew :androidApp:testDebugUnitTest     # JVM unit tests (StringsCoverageTest, BeeperToneTest, EntitlementReconcilerTest)
./gradlew :androidApp:lintRelease           # Android Lint on the release variant
```

### Instrumentation tests

`androidApp/src/androidTest/kotlin/lg/android/SmokeTest.kt` (JUnit4 +
`androidx.test`, `androidTestImplementation` only — none of it ships in the
app) launches `MainActivity` and asserts all 15 catalogue tiles are present,
opens `GameActivity` for `snake` and injects a tap + swipe plus a
pause/resume and rotation cycle, and opens `PaywallActivity` for `sudoku`
and closes it with the back button.

It always compiles on this dev machine:

```sh
./gradlew :androidApp:compileDebugAndroidTestKotlin
```

Running it requires a connected device or emulator (this dev box has
neither):

```sh
./gradlew :androidApp:connectedDebugAndroidTest
```

## Footprint report

`tools/footprint.sh` builds on `:androidApp:assembleRelease`'s output and
reports `apkanalyzer`'s download size and top DEX packages, failing if the
download size exceeds the internal 5 MiB threshold (PLAN.md §7 targets
1.5–3 MB; the release build has stayed around 250–280 KB through M5):

```sh
./gradlew :androidApp:assembleRelease && tools/footprint.sh
```

The report is printed to stdout only (nothing is written to disk); redirect
it yourself (`tools/footprint.sh | tee footprint.txt`) if you want a copy.

## Signing (M5 status)

`assembleRelease`/`bundleRelease` are signed with the **debug keystore** by
default, so they always produce an installable, measurable build with zero
setup — this is what CI and this dev machine use.

To sign a real release with your own key instead:

1. Generate a keystore if you don't have one yet:
   ```sh
   keytool -genkeypair -v -keystore release.keystore -alias lightgames \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `androidApp/keystore.properties` (already gitignored — **never**
   commit this file or the keystore itself):
   ```properties
   storeFile=/absolute/or/relative/path/to/release.keystore
   storePassword=...
   keyAlias=lightgames
   keyPassword=...
   ```
   `storeFile` is resolved relative to `androidApp/` when given as a
   relative path.
3. Build as usual — `androidApp/build.gradle.kts` checks for
   `keystore.properties` at configuration time: if present, `release` is
   signed with it; if absent, it falls back to the debug keystore. No other
   step is needed, and nothing about the fallback path changes for anyone
   who doesn't create the file.

`androidApp/proguard-rules.pro` stays minimal (`-repackageclasses ''` +
`-allowaccessmodification`, both size/obfuscation-only R8 flags); `core` has
no reflection so it needs no keep rules, and neither does this module.
