# Lightweight Games

One small mobile app (Android + iOS) with 15 minimal, offline games: 3 free, 12 unlocked by a single non-consumable purchase. No ads, no tracking, no account, no network needed to play.

- `PLAN.md` — the approved plan: catalog, architecture, IAP, footprint strategy, milestones, risks.
- `docs/JOURNAL.md` — session-by-session history of decisions and results.
- `docs/ARCHITECTURE.md` — how the code is organised and how to add a game.
- `docs/QA_CHECKLIST.md`, `docs/BILLING_TESTING.md`, `docs/STORE_LISTING.md` — release material.

## Layout

```
core/        Kotlin Multiplatform: game rules, Scene primitives, GameSession, entitlement (178 tests)
androidApp/  Android app: Views + Canvas, Play Billing 8, no AndroidX UI (see androidApp/README.md)
iosApp/      iOS app: UIKit + CoreGraphics, StoreKit 2, XcodeGen spec (see iosApp/README.md)
preview/     Dev-only JVM renderer: every game to PNG, no device needed
tools/       env.sh, footprint.sh, ios_size.sh, icon and word-list generators
legacy/      The previous approach (one standalone Android app per game); kept for reference only
```

## Build

```sh
source tools/env.sh                      # JDK 21 + Android SDK paths (see tools/env.sh)
./gradlew :core:jvmTest                  # core tests, no Android SDK needed
./gradlew :androidApp:assembleRelease && tools/footprint.sh
./gradlew :preview:run                   # PNG previews into preview/out/
```

iOS needs a Mac: `cd iosApp && xcodegen generate`, then build in Xcode (`iosApp/README.md`).
