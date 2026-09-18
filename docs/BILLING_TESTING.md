# Testing "unlock_all" on Android (Play Billing)

None of this can run in this repository's environment (no device, no Play
Console access, no signed release track). This document is the runbook for
whoever does have that access, and is referenced by `docs/QA_CHECKLIST.md`'s
purchase section.

## 1. One-time Play Console setup

1. Create the app in Play Console (if not already done) and upload at least
   one build to the **Internal testing** track (`bundle { }` in
   `androidApp/build.gradle.kts` already produces a split AAB; run
   `./gradlew :androidApp:bundleRelease` and upload the `.aab`, signed with
   the real release key once M5 wires it up — the debug-signed build in
   M0–M4 is enough for internal testing, Play accepts any valid signature on
   that track).
2. **Monetize → Products → In-app products**: create a managed (non-
   consumable) product with product id exactly `unlock_all` (must match
   `lg.core.store.Entitlement.PRODUCT_ID`), set its price, activate it.
3. **Setup → License testing**: add the Google accounts of every human who
   will test purchases to the license testers list. Accounts in this list
   can "buy" real products without being charged, and can be given a
   specific test response (see below).
4. Internal testing requires at least one tester to have accepted the
   internal-testing opt-in link and installed the app **from Play**, not via
   `adb install` — Play Billing refuses to return real product details or
   let a sideloaded build purchase anything for a product tied to an app the
   Play Store doesn't think that account has access to.

## 2. Test responses (license testers only)

In **Setup → License testing**, each license tester can be assigned a
"testing response" that the *next* purchase for that account will use,
without needing a sandbox/staging concept:

- **RFC (always approve, "always approves")** — completes instantly as
  `PURCHASED`. Use this for the main "buy → unlocked" path and for exercising
  `PlayBilling.onPurchasesUpdated`'s `PURCHASED` branch (acknowledge + grant).
- **Slow (approves after a few minutes)** — the purchase starts in `PENDING`
  and later transitions to `PURCHASED` server-side (mimics real pending
  payment methods, e.g. some carrier billing or bank transfers). Use this to
  verify:
  - `PlayBilling.onPurchasesUpdated`'s `PENDING` branch shows the "purchase
    pending" toast (`StringKey.PURCHASE_PENDING`) and does **not** unlock
    anything yet.
  - Killing and relaunching the app while still pending: the game stays
    locked (no premature grant).
  - Once Play flips it to `PURCHASED` server-side, the *next* silent
    revalidation or explicit "Restore purchases" (do not wait 7 days —
    force it via "Restore purchases" in Settings) picks it up and unlocks.
- **Always decline** — the purchase flow ends in an error response. Verifies
  `PlayBilling.onPurchasesUpdated`'s failure branch shows
  `StringKey.PURCHASE_FAILED` and leaves the local entitlement untouched.

Test card selection lives at the account level in Play Console, not in the
app; there is nothing to configure in `PlayBilling.kt` to switch between
them.

## 3. Refund / revocation test

1. As a license tester, buy `unlock_all` with the "always approve" response.
   Confirm the catalog unlocks immediately.
2. In Play Console, **Order management**, find that tester's order for
   `unlock_all` and issue a refund (or use `adb shell pm clear
   com.android.vending` + revoke from the Play web console's purchase
   history for that test account — Order management is the reliable path).
3. Do **not** expect automatic revocation: per PLAN.md §6, the app never
   polls in the background. Revocation only happens the next time
   `PlayBilling.restore()` runs — either the user taps "Restore purchases",
   or (if 7 days have passed since the last successful verification) the
   catalog's silent revalidation on `MainActivity.onResume`. For testing,
   force it: open Settings → "Restore purchases".
4. Expect: `EntitlementReconciler.reconcile` sees `queryOk = true`,
   `ownedTokens = []`, `current = PLAY` → `Revoke`. `Entitlement.revoke()`
   runs, the catalog's tiles show padlocks again on the next
   `MainActivity.onResume`. The Settings toast reads
   `StringKey.NOTHING_TO_RESTORE` (there is no separate "revoked" string;
   from the user's point of view a revoked purchase and "nothing found" look
   identical, which is intentional — PLAN.md §6 doesn't ask for a distinct
   message).
5. Negative check: repeat the same refund test on a build that has
   `Entitlement.source() == APPSTORE` forced in a debugger/test hook (not
   reachable from the Android UI in practice, since Android only ever grants
   `PLAY`) to confirm `reconcile` would `Keep` rather than `Revoke` — this
   is already covered by `EntitlementReconcilerTest` and does not need a
   device.

## 4. Offline / "store unreachable" checks

These are also in `docs/QA_CHECKLIST.md`'s "Offline first" section; repeated
here with the specific billing angle:

1. With the device in airplane mode, cold-launch the app. Confirm:
   - No network permission is requested or needed (`aapt2 dump badging` on
     the release APK shows only `uses-permission: name='com.android.vending.BILLING'`,
     the normal, non-dangerous permission the Billing Library's own manifest
     declares for its Play Store IPC — never `INTERNET`).
   - The catalog opens immediately, free games are playable, locked games
     show padlocks.
   - `PlayBilling` never connects at launch (`App.billing` is a `by lazy`
     that `Application.onCreate` never touches).
2. Still in airplane mode, tap a locked tile to open the paywall. Confirm:
   - No spinner, no hang — `PlayBilling.queryProduct` calls back with
     `null` quickly once `connect()`'s `BillingClientStateListener` reports
     failure (or, on some devices, the callback simply never manages to
     reach the Play Store process and the single `SERVICE_DISCONNECTED`
     retry also fails).
   - The offline notice (`StringKey.OFFLINE_NOTICE`) is shown, the price
     field is hidden, and the "Unlock all" hold button is disabled
     (`HoldToConfirmButton.isEnabled == false` — holding it does nothing).
   - Back still closes the paywall immediately.
3. Still offline, tap "Restore purchases" (paywall or Settings). Confirm it
   returns promptly (not a long hang) and shows the fallback toast — the
   local entitlement is left exactly as it was before the tap.
4. Turn airplane mode back on right after a successful purchase (online),
   force-kill and relaunch the app. Confirm the game is still unlocked purely
   from the local `Entitlement` cache — no store round-trip is required to
   play.

### 4 bis. A buyer never loses access without acting

The silent 7-day revalidation can only grant or confirm (`allowRevoke = false` in `PlayBilling.silentRevalidate`); only the explicit "Restore purchases" button may revoke after a successful "nothing owned" answer.

- [ ] Buy with the license tester, then switch the phone to a Google account that did not buy. Open the app repeatedly (force the check by moving the date 8 days ahead): every game stays unlocked.
- [ ] Same account, airplane mode for a week of simulated time: still unlocked.
- [ ] Explicit "Restore purchases" with the non-buyer account online: the app reports "no purchase found" and locks again (expected: this is the refund path, user-initiated).

## 5. What only a real device can prove

Everything above requires a physical or emulated Android device signed into
a Play Store account with the app installed from an internal-testing (or
later) track; none of it can be exercised from this repository's headless
build environment. `EntitlementReconcilerTest` (`:androidApp:testDebugUnitTest`)
covers the pure decision logic (`Grant`/`Revoke`/`Keep`) without a device,
but the actual `BillingClient` wiring, the parental-gate UI, and the Play
Store's own purchase/refund/pending-state machine are unverified until this
runbook is followed on a device.
