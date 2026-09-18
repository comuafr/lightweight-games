# REVIEW — human checklist before publishing Snake

Complete every item before submitting to Google Play.

## Legal / trademark
- [ ] App name is generic and NOT a registered trademark.
- [ ] No trademarked name anywhere (title, package, code, listing, screenshots).
- [ ] Mechanics only were reimplemented; no copied characters/levels/logos.
- [ ] Publisher name "Lightweight Games" verified available and not conflicting.
- [ ] Consider professional IP legal review. (Agent is not a lawyer.)

## Build & quality (auto-checked in CI, confirm)
- [ ] `./gradlew test` passes.
- [ ] `./gradlew lint` passes.
- [ ] `./gradlew bundleRelease` succeeds.
- [ ] Measured APK size: __________ (must be < 1.5 MB).
- [ ] Game.kt has no Android imports (decoupled, tested).

## Manual play test (human only — agent cannot do this)
- [ ] Plays correctly on a physical device.
- [ ] Touch controls feel right.
- [ ] Audio latency acceptable.
- [ ] High score persists across restarts.
- [ ] Handles rotation / interruptions (calls, backgrounding) gracefully.

## Pricing (PAID app)
- [ ] Google Play payments/merchant profile created (one-time, per developer account).
- [ ] App set to "Paid"; price tier US$0.49 (auto-converts to local tiers).
- [ ] Confirmed no Play Billing / IAP code was added (would break zero-dependency rule).
- [ ] Tax & compliance info completed in Play Console.

## Store listing
- [ ] Short description <= 80 chars; full <= 4000 chars.
- [ ] Icon 512x512, feature graphic 1024x500, >= 2 phone screenshots.
- [ ] Data safety form: "No data collected, no data shared".
- [ ] Privacy policy URL provided (one-pager stating no data collection).
- [ ] Content rating questionnaire completed.
- [ ] Listing sells the angle: pay once, no ads, no tracking, no permissions,
      fully offline, tiny download.

## Flagged by agent
- NAME: "Snake" is a generic, descriptive term for this public-domain mechanic
  (grow-by-eating on a grid) and is used by many published apps. It is almost
  certainly safe, but verify the exact title isn't confusingly close to a
  specific competitor and isn't a registered trademark in your target markets.
  No branded characters, levels, or sounds are used — mechanics only.
- BUILD NOT RUN LOCALLY: this game was authored in an environment without the
  Android SDK/Gradle. The pure game logic (Game.kt) was compiled and its 9 test
  scenarios were executed and passed with kotlinc; the full Android build,
  lint, AAB and size budget run in CI (GitHub Actions). Confirm the first CI
  run is green before publishing.
- SIZE: not measured locally. Expected well under the 1.5 MB budget (pure
  Kotlin, vector icon, ~7 KB audio), but read the actual figure from the CI
  "APK size budget" step.
- AUDIO/FEEL: sound timing, touch/swipe feel, and rotation/backgrounding
  behaviour can only be judged on a physical device — see the manual checklist.
- ICON: the launcher icon is an original vector (green snake + red food pip),
  not derived from any branded asset. A 512×512 store icon still needs to be
  exported to branding/ for the listing.
