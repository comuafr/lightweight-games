# {{APP_NAME}}

{{ONE_LINE_PITCH}}

A lightweight, pure-Kotlin Android game. No engine, no third-party libraries, no
ads, no tracking, no permissions — fully offline. Paid app (US$0.49).

## Features
{{FEATURES_LIST}}

## Controls
{{CONTROLS_LIST}}

## Tech / constraints
- Pure Kotlin, zero third-party runtime dependencies, no NDK.
- `SurfaceView` + `Canvas` rendering, `SoundPool` audio, `SharedPreferences` saves.
- Single `Activity` (not AppCompat). `minSdk 24`, `targetSdk 35`.
- Game logic (`Game.kt`) is decoupled from Android and unit-tested on the JVM.
- Release download size: {{SIZE}} (budget < 1.5 MB).

## Build
This game is part of the monorepo and shares the repo-root Gradle wrapper. Run
from the repo root:
```sh
(cd games/{{SLUG}} && python3 tools/gen_sounds.py)  # regenerate res/raw WAVs
./gradlew -p games/{{SLUG}} test            # JVM unit tests of the game logic
./gradlew -p games/{{SLUG}} lint            # static analysis
./gradlew -p games/{{SLUG}} assembleRelease # APK
./gradlew -p games/{{SLUG}} bundleRelease   # AAB for Play upload
```

## Layout
- `app/src/main/java/com/lightweight/{{SLUG_PKG}}/Game.kt` — pure logic, no Android.
- `…/GameView.kt` — loop, rendering, input. `…/Sound.kt` — audio. `…/MainActivity.kt`.
- `app/src/test/.../GameTest.kt` — JVM unit tests.

See `REVIEW.md` for the pre-publish human checklist. Proprietary; see `LICENSE`.
