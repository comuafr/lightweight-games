# REVIEW — human checklist before publishing {{APP_NAME}}

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
{{AGENT_FLAGS}}
