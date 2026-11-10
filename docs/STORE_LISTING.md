# Store listing (draft)

Same content on Google Play and the App Store, adapted to each console's fields.

## Names

Game titles are original (renamed on 2026-09-18 to stay clear of any classic-game or brand name); the mapping to the internal ids is in `docs/GAME_NAMES.md`.
- App name: **Light Games** (EN) / **Jeux Légers** (FR)
- Bundle / application id: `fr.comua.lightgames`
- In-app product: `unlock_all`, non-consumable, "Unlock every game" / "Débloquer tous les jeux"

## Descriptions, titles and release notes
The ready-to-paste texts (EN and FR, character limits checked) and the full list of Play Console fields and answers are in `store/play/listing/` (start with `store/play/listing/README.md`). They are the reference: do not copy them here.

## Categories and ratings
- Google Play: Games > Casual. Content rating questionnaire: no violence, no user interaction, no data collection. Target audience: declare "everyone" including children only after reviewing the Designed for Families policy (no ads, no data collection, IAP disclosed). The app has no INTERNET permission.
- App Store: Games > Family, secondary Puzzle. Age rating 4+. Do not opt into the Kids category (extra IAP constraints); keep the parental gate anyway.

## Privacy declarations
- Google Play Data safety: no data collected, no data shared, no security practices to declare beyond "data is not encrypted in transit" being not applicable (no transit). Purchases are handled by Google Play.
- App Store privacy nutrition label: "Data Not Collected". `PrivacyInfo.xcprivacy` declares UserDefaults (reason CA92.1) and no tracking.

## Assets (generated)
`python3 tools/icons/gen_store_art.py` writes `store/play/icon_512.png` and `feature_1024x500_{en,fr}.png` (same mark as the launcher icon). Privacy policy: `docs/PRIVACY_POLICY.md` (to host at a public URL; fill in the contact e-mail first).

## Screenshots
Generate with `./gradlew :preview:run` (JVM renderer, PNG per game) for drafts, then replace with real device captures for the final listing: catalog, Gobbler, Fusion, Wall Smash, one two-player game (Paddles), one puzzle (Nine by Nine), the unlock screen with the parental gate.
