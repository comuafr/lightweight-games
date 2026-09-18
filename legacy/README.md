# Lightweight Games — monorepo + factory kit

A single repo holding many small, dependency-free, **paid** Android games, plus
the kit/agent that produces them.

The pitch for every game: **pay once (US$0.49), then it's yours** — no ads, no
tracking, no permissions, fully offline, and a download well under 1 MB.

## What's here

```
README.md            this file
gradlew[.bat]        ONE shared Gradle wrapper for every game
gradle/wrapper/      committed wrapper jar + properties
.github/workflows/build.yml   CI: auto-discovers games/* and builds each (matrix)
new_game.py          scaffolds a new game into games/<slug>/ from templates/
agent/
  AGENT.md           system prompt: role, hard constraints, trademark + pricing rules
  WORKFLOW.md        step-by-step procedure the agent follows per game
  BACKLOG.md         vetted, trademark-safe game concepts to work through
bootstrap/
  bootstrap_vps.sh   installs JDK 17, Android SDK 35, gh, python on a fresh VPS
  verify_env.sh      confirms the toolchain is present
templates/           the per-game starter (a self-contained Gradle app project)
  settings.gradle.kts  build.gradle.kts  gradle.properties
  app/ …               pure-Kotlin app skeleton (Game/GameView/Sound/MainActivity)
  tools/gen_sounds.py  8-bit sound generator
  LICENSE README.md REVIEW.md STORE_LISTING.md .gitignore
games/
  snake/             reference game (Snake) — full project, no own wrapper/CI
  …                  one folder per game
```

Each game under `games/<slug>/` is a self-contained Gradle build (its own
`settings.gradle.kts`, `applicationId`, version, icon, docs) so it ships as its
own Play Store app — it just shares the repo-root wrapper and the root CI.

## Bootstrapping the toolchain

```sh
cd bootstrap
./bootstrap_vps.sh        # JDK 17, Android SDK 35, gh, python3
source ~/.bashrc
./verify_env.sh           # confirms everything is present
```

Give the agent `agent/AGENT.md` as its system prompt, `agent/WORKFLOW.md` as its
operating procedure, and `agent/BACKLOG.md` as its task list.

## Add a game

```sh
python3 new_game.py snake "Snake" snake   # -> games/snake/ (package com.lightweight.snake)
# fill the TODOs per WORKFLOW.md: Game.kt + GameTest.kt, GameView.kt, sounds, icon, docs
cd games/snake && python3 tools/gen_sounds.py && cd -
./gradlew -p games/snake test
./gradlew -p games/snake lint
./gradlew -p games/snake bundleRelease     # AAB to upload to Play
```

CI then picks the new game up automatically (no workflow edit needed) and runs
the guardrails, tests, build and size budget for it.

## Hard rules (full text in agent/AGENT.md)

- Pure Kotlin, zero third-party runtime deps, no NDK.
- SurfaceView+Canvas, SoundPool, SharedPreferences. Single Activity, no AppCompat.
- targetSdk 35, AAB, R8 full mode, release APK < 1.5 MB.
- Game logic decoupled from Android and JVM-unit-tested.
- NEVER a trademarked game name. Generic names only. Flag for legal review.
- Paid US$0.49, no ads, no IAP, no tracking. (No Play Billing — price is set in
  Play Console, not in code.)

## What the agent CANNOT do

It can prove a game is correct (JVM tests), builds, and measures its real size.
It CANNOT judge playability, touch feel, or audio latency — those require a
human on a physical device. Every game ships with a `REVIEW.md` listing exactly
what the human must verify before publishing (name/trademark decision, measured
size, merchant-account/pricing setup).
