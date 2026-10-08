# Manual QA checklist

Run this before every release, on one physical Android device and one physical iPhone.

## Offline first (airplane mode ON for the whole section)
- [ ] Cold launch < 1 s, catalog visible immediately, no spinner, no network error.
- [ ] Gobbler, Fusion and Wall Smash (the three free games) are playable end to end (start, play, game over, restart).
- [ ] Locked tiles show a padlock; tapping one opens the unlock screen with the "offline" notice.
- [ ] The unlock screen never blocks: back/close always returns to the catalog.
- [ ] Best scores survive a kill + relaunch.

## Purchase (license tester / sandbox account)
- [ ] Price is displayed from the store (localized currency).
- [ ] Parental gate must be passed before the store sheet appears.
- [ ] Successful purchase unlocks every game immediately, no relaunch needed.
- [ ] Airplane mode ON, kill + relaunch: still unlocked.
- [ ] Different Google / Apple account on the phone, app opened online several times: still unlocked (silent checks never revoke).
- [ ] Uninstall, reinstall, "Restore purchases": unlocked again.
- [ ] Pending purchase (Play test card "slow", iOS Ask to Buy): app shows "waiting", games stay locked, no crash; approval later unlocks.
- [ ] Refund (Play console / StoreKit config): next store check revokes the entitlement.

## Lifecycle
- [ ] Every game stays locked in portrait; turning the phone does not rotate or reset it.
- [ ] Same play zone on a small phone, a tall phone and a tablet (same board, same speed, only the zoom differs).
- [ ] Background then foreground during a real-time game: game is paused with a Resume overlay, never auto-resumes.
- [ ] Incoming call during a game: same behaviour as background.
- [ ] Resumable games (Fusion, Nine by Nine, ...) restore the board after a kill + relaunch.

## Two-player games (Paddles, Three in Line, Drop Four, Twins, Flip Discs)
- [ ] Two fingers at the same time work on both halves.
- [ ] System edge gestures (back swipe, home indicator) are not triggered by normal play.
- [ ] Two-player games give each player a readable, correctly oriented half of the play zone (top and bottom).

## Accessibility and kids
- [ ] Every tappable target is at least 48 pt.
- [ ] Dark theme and light theme both readable.
- [ ] Largest system font size does not break the catalog or the overlays.
- [ ] No text-heavy screen anywhere in the game flow.

## Battery and footprint
- [ ] Catalog screen triggers no continuous redraw (Android: `adb shell dumpsys gfxinfo <pkg>` frames stay flat; iOS: Instruments Core Animation).
- [ ] Game loop stops when the game is paused or over.
- [ ] `tools/footprint.sh` report attached to the release notes (Android download size under threshold).
- [ ] `tools/ios_size.sh` App Thinning Size Report attached (iOS under threshold).
- [ ] No network permission on Android (`aapt dump permissions`), no outgoing connection on iOS except the App Store during purchase.
