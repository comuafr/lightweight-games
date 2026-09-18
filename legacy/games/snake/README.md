# Snake

Guide the snake to eat, grow longer, and don't crash into the walls or yourself.

A lightweight, pure-Kotlin Android game. No engine, no third-party libraries, no
ads, no tracking, no permissions — fully offline. Paid app (US$0.49).

## Features
- Classic grow-the-snake gameplay on a 17×24 grid.
- Swipe controls; eat food to grow and score.
- High score saved locally (survives restarts).
- Two synthesized sound effects; no music, no clutter.
- Instant launch, runs on anything from Android 7 (API 24) up.

## Controls
- Swipe up / down / left / right to turn.
- 180° reversals into your own neck are ignored (no accidental deaths).
- Tap anywhere on the game-over screen to play again.

## Tech / constraints
- Pure Kotlin, zero third-party runtime dependencies, no NDK.
- `SurfaceView` + `Canvas` rendering, `SoundPool` audio, `SharedPreferences` saves.
- Single `Activity` (not AppCompat). `minSdk 24`, `targetSdk 35`.
- Game logic (`Game.kt`) is decoupled from Android and unit-tested on the JVM.
- Release download size: measured by CI on each build (budget < 1.5 MB).

## Build
Part of the monorepo; shares the repo-root Gradle wrapper. From the repo root:
```sh
(cd games/snake && python3 tools/gen_sounds.py)  # regenerate res/raw WAVs
./gradlew -p games/snake test            # JVM unit tests of the game logic
./gradlew -p games/snake lint            # static analysis
./gradlew -p games/snake assembleRelease # APK
./gradlew -p games/snake bundleRelease   # AAB for Play upload
```

## Layout
- `app/src/main/java/com/lightweight/snake/Game.kt` — pure logic, no Android.
- `…/GameView.kt` — loop, rendering, input. `…/Sound.kt` — audio. `…/MainActivity.kt`.
- `app/src/test/.../GameTest.kt` — JVM unit tests.

See `REVIEW.md` for the pre-publish human checklist. Proprietary; see `LICENSE`.
