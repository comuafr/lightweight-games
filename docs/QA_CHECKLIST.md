# Manual QA checklist

Run this before every release, on one physical Android device and one physical iPhone.

## Offline first (airplane mode ON for the whole section)
- [ ] Cold launch < 1 s, catalog visible immediately, no spinner, no network error.
- [ ] Snake, 2048 and Bricks are playable end to end (start, play, game over, restart).
- [ ] Locked tiles show a padlock; tapping one opens the unlock screen with the "offline" notice.
- [ ] The unlock screen never blocks: back/close always returns to the catalog.
- [ ] Best scores survive a kill + relaunch.

## Purchase (license tester / sandbox account)
- [ ] Price is displayed from the store (localized currency).
- [ ] Parental gate must be passed before the store sheet appears.
- [ ] Successful purchase unlocks every game immediately, no relaunch needed.
- [ ] Airplane mode ON, kill + relaunch: still unlocked.
- [ ] Uninstall, reinstall, "Restore purchases": unlocked again.
- [ ] Pending purchase (Play test card "slow", iOS Ask to Buy): app shows "waiting", games stay locked, no crash; approval later unlocks.
- [ ] Refund (Play console / StoreKit config): next store check revokes the entitlement.

## Lifecycle
- [ ] Rotate during a real-time game (Snake, Bricks): layout adapts, no reset, no crash.
- [ ] Rotate during a turn-based game: board keeps its state.
- [ ] Background then foreground during a real-time game: game is paused with a Resume overlay, never auto-resumes.
- [ ] Incoming call during a game: same behaviour as background.
- [ ] Resumable games (2048, Sudoku, ...) restore the board after a kill + relaunch.

## Two-player games (Paddles, Tic-Tac-Toe, Four in a Row, Pairs, Reversi)
- [ ] Two fingers at the same time work on both halves.
- [ ] System edge gestures (back swipe, home indicator) are not triggered by normal play.
- [ ] Portrait and landscape both give each player a readable, correctly oriented zone.

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
