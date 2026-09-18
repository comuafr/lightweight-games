# iOS app (`iosApp/`)

Swift + UIKit + Core Graphics shell around the `core` Kotlin Multiplatform
module (see `../docs/ARCHITECTURE.md` and `../PLAN.md` §5, §9 for the
product-level picture). As of M5: the full 15-game catalogue (M0-M4),
sound/haptics settings (M1), the "unlock_all" non-consumable purchase with
paywall, restore, and offline handling (M2, PLAN.md §6), and release
polish -- localized titles/difficulty strings for every game, the core's
language tag wired from the device locale, the App Store icon, and
release-configuration build settings (M5, PLAN.md §9, see "Release
checklist" below).

**This project was written and reviewed entirely on Linux** (no Xcode, no
Swift compiler, no macOS available). Everything below is believed correct
against the Kotlin/Native Objective-C export rules and the actual
`core/src/commonMain/kotlin/lg/core/` sources, but none of it has been
compiled by Xcode. The "things that may need fixing" section at the bottom
lists exactly what to double-check on first build.

## Deviation from PLAN.md §3: no committed `.xcodeproj`

PLAN.md §3 lists a committed `LightweightGames.xcodeproj`. Instead, this
directory ships an [XcodeGen](https://github.com/yonaskolb/XcodeGen) spec,
`project.yml`, and the `.xcodeproj` is generated locally and **not**
committed (see `.gitignore`: `iosApp/*.xcodeproj/`). A hand-written
`.xcodeproj` is a large, easy-to-corrupt XML/pbxproj blob that cannot be
verified without Xcode; `project.yml` is small, readable, diffable, and
regenerates deterministically. This is an approved deviation — regenerate
the project any time `project.yml` changes:

```sh
cd iosApp
xcodegen generate
```

## Prerequisites (on a Mac)

- Xcode 26+ (PLAN.md §2: targets iOS ≥ 15.0, built with the 2026-era SDKs).
- JDK 21. `../tools/env.sh` pins one via `$HOME/.jdks/jdk-21` on the Linux
  dev box; on a Mac, install Temurin 21 (or any JDK 21) and make sure
  `JAVA_HOME` or `/usr/libexec/java_home -v 21` can find it — the Gradle
  build phase below tries `java_home` automatically.
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`.
- The repo itself, with `core/` present (this app links `GamesCore`, built
  from `core/`).

## First build

```sh
cd iosApp
xcodegen generate
open LightweightGames.xcodeproj
```

Select the `LightweightGames` scheme and Run. The app target has a
"Build & embed GamesCore.xcframework (Gradle)" Run Script build phase
(defined in `project.yml`, `targets.LightweightGames.preBuildScripts`) that
runs before Compile Sources and invokes:

```sh
./gradlew :core:assembleGamesCoreDebugXCFramework     # or ...ReleaseXCFramework for Release / archive
```

from the repo root (`$SRCROOT/..`) and copies the result to
`iosApp/Frameworks/GamesCore.xcframework`. That XCFramework (device `ios-arm64`
and simulator `ios-arm64-simulator` slices, static) is declared in
`project.yml` as a plain framework dependency of the app target, so Xcode
picks the right slice for whatever you build; nothing is embedded because
the framework is static. The **first** build is slow (Gradle downloads the
Kotlin/Native toolchain and compiles both slices); later builds are
incremental. `iosApp/Frameworks/` is `.gitignore`d.

## `LG_USE_PREBUILT=1`: skip Gradle (CI / offline / no JDK)

Set the `LG_USE_PREBUILT` environment variable to `1` (scheme environment
variable, User-Defined build setting, or `xcodebuild ... LG_USE_PREBUILT=1`)
and the pre-build script only checks that
`iosApp/Frameworks/GamesCore.xcframework` already exists. Produce it with:

```sh
source tools/env.sh   # JDK 21 on PATH
./gradlew :core:assembleGamesCoreReleaseXCFramework
mkdir -p iosApp/Frameworks && cp -R core/build/XCFrameworks/release/GamesCore.xcframework iosApp/Frameworks/
```

This is exactly what `.github/workflows/ios.yml` does on a GitHub macOS runner.

## Running tests

```sh
xcodebuild test \
  -project iosApp/LightweightGames.xcodeproj \
  -scheme LightweightGames \
  -destination 'platform=iOS Simulator,name=iPhone 15'
```

or Product ▸ Test (⌘U) in Xcode. `LightweightGamesTests/SmokeTests.swift`
covers PLAN.md §8's iOS smoke-test requirement: a headless Snake
`GameSession` runs for 60 ticks without an empty or overflowing `Scene`,
the catalogue shows exactly one tile per `GameRegistry.shared.all` entry
(15 as of M4/M5 — the test reads the count from `GameRegistry` itself, so
it never needs updating when a game is added or removed), and every
`StringKey` ordinal (0..<44) resolves to a non-empty, actually-translated
string in `Localizable.xcstrings` (`testEveryStringKeyOrdinalResolvesToATranslatedString`,
which catches the same class of bug as `tools/check_string_keys.py` below,
at test time instead of via a standalone script).

## Testing purchases

The "unlock_all" non-consumable (PLAN.md §6) has three independent test
paths; use whichever fits what you're checking.

### 1. StoreKit configuration in the scheme (local, no Apple ID)

`Products.storekit` (this directory) is wired into the `LightweightGames`
scheme's Run action (`project.yml`, `schemes.LightweightGames.run.storeKitConfiguration`).
With it selected, running the app in Xcode uses **Xcode's local StoreKit
testing environment** instead of the real App Store: no network, no signed-in
Apple ID, purchases "succeed" instantly against the fake product in
`Products.storekit` (id `unlock_all`, $2.99, en/fr localizations).

- Manage the simulated environment from **Xcode ▸ Debug ▸ StoreKit** while
  the app is running: clear purchase history, insert or refund a
  transaction, toggle Ask-to-Buy, or make a specific transaction fail.
- You can also open `Products.storekit` directly in Xcode's StoreKit
  Configuration editor (double-click the file) to tweak price, add more
  products, or enable "Ask to Buy" globally for testing the pending-purchase
  path (`StoreManager.PurchaseOutcome.pending`).
- If the Run scheme's StoreKit configuration isn't picked up automatically
  (older Xcode/XcodeGen combinations, or `storeKitConfiguration` not
  supported by the installed XcodeGen — see the `options` comment at the top
  of `project.yml`), set it manually: scheme editor ▸ Run ▸ Options ▸
  StoreKit Configuration ▸ `Products.storekit`.

### 2. `SKTestSession` in XCTest (automated, CI-friendly)

`LightweightGamesTests/StoreKitTests.swift` drives the same
`Products.storekit` configuration programmatically via
`SKTestSession(configurationFileNamed:)`, with dialogs disabled
(`disableDialogs = true`) so purchases complete without any UI:

- `testPurchaseUnlocksEntitlement` — loads the product, purchases it, and
  asserts `Entitlement.isUnlocked() == true` with `source() == .appstore`.
- `testClearingTransactionsThenRestoreLocksEntitlement` — purchases, then
  calls `session.clearTransactions()` to simulate the purchase no longer
  existing for the account (stand-in for a refund), then calls
  `StoreManager.restore()` and asserts the entitlement is revoked.
- `EntitlementReconcilerTests.swift` covers the pure
  `EntitlementReconciler.reconcile` decision table directly (owned → grant;
  sync ok + not owned + App Store source → revoke; sync ok + not owned +
  Play source → keep; sync failed → keep) with no StoreKit dependency at
  all, so it's fast and safe to run on every commit.

Run with `xcodebuild test` (see "Running tests" above) or ⌘U.
`StoreKitTest.framework` is linked weakly into `LightweightGamesTests`
(`project.yml`) since only this file uses it.

### 3. Sandbox / TestFlight (closest to production)

Once the matching "unlock_all" non-consumable exists in App Store Connect
(same product id, price, and localizations as `Products.storekit` — see the
`options` comment in `project.yml`):

- Create a **Sandbox Apple ID** in App Store Connect ▸ Users and Access ▸
  Sandbox ▸ Testers, sign into it on-device under Settings ▸ App Store ▸
  Sandbox Account (not the regular Apple ID), then run a build directly
  from Xcode (not the StoreKit configuration — remove/ignore the scheme's
  `storeKitConfiguration` for this path, or it takes precedence) to buy
  against the real sandbox environment.
- **Ask to Buy**: enable "Ask to Buy" on the sandbox tester (Family Sharing
  settings for that sandbox account in App Store Connect) to exercise
  `Product.PurchaseResult.pending` → `StringKey.PURCHASE_PENDING` → later
  grant via `Transaction.updates` (`StoreManager.startTransactionListener`)
  once approved.
- **Refund**: in Xcode, Debug ▸ StoreKit ▸ Manage Transactions (or
  Xcode ▸ Window ▸ Devices and Simulators-adjacent transaction manager)
  lets you refund a sandbox transaction; confirm the app locks back down
  either via the `Transaction.updates` listener (foreground) or
  `StoreManager.restore()` (tap "Restore purchases" in Settings or the
  paywall) without needing to relaunch.
- Confirm the **offline** path: enable Airplane Mode, open the paywall for
  a locked game — `StoreManager.loadProduct()` should return `nil`, showing
  `StringKey.OFFLINE_NOTICE` with the buy button disabled, while the
  "Restore purchases" button and the close button remain tappable
  (`isModalInPresentation = false`, no relaunch nag).
- Confirm a **previously-unlocked, now-offline** relaunch stays unlocked:
  buy while online, force-quit, enable Airplane Mode, relaunch — the
  catalog should show every game unlocked (`checkCurrentEntitlementsAtLaunch`
  is local-only and never revokes on an empty/offline cache).

## Localization (`StringKey` ↔ `Localizable.xcstrings`)

`lg.core.scene.StringKey` (`core/src/commonMain/kotlin/lg/core/scene/StringKey.kt`)
is the single source of truth for every string the core can draw; the core
itself holds no translations (PLAN.md §4). `Strings.swift`'s
`keysByOrdinal` mirrors that enum's declaration order exactly — index `i`
is the lower_snake_case `.xcstrings` key for the Kotlin case with ordinal
`i` — and `Localizable.xcstrings` (en source language + fr) must carry a
translated entry for every one of those keys, plus the handful of
iOS-shell-only extras (`app_name`, `close`, `version`) that have no
`StringKey` equivalent.

Two independent checks guard this three-way mapping (Kotlin enum order ↔
Swift ordinal table ↔ `.xcstrings` keys) from silently drifting whenever a
game is added:

- `iosApp/tools/check_string_keys.py` — a standalone script (Python 3,
  stdlib only, runs on Linux/CI/Mac alike, no Xcode needed) that parses all
  three sources and exits non-zero on any mismatch:
  ```sh
  python3 iosApp/tools/check_string_keys.py
  ```
- `SmokeTests.testEveryStringKeyOrdinalResolvesToATranslatedString` — an
  XCTest that walks every ordinal at runtime and asserts
  `Strings.string(forOrdinal:)` is non-empty and differs from its own raw
  key (the value `NSLocalizedString` falls back to when a translation is
  missing from the bundle, which the static script can't observe since it
  never loads the compiled bundle).

Run the Python check after touching `StringKey.kt`, `Strings.swift`, or
`Localizable.xcstrings` — it's cheap and doesn't need a Mac.

## App icon

`Assets.xcassets/AppIcon.appiconset/Contents.json` references a single
1024×1024, no-alpha `ios_AppIcon.png`, generated procedurally (no
external image assets, no Pillow dependency) and committed to the repo (a
single ~8 KB PNG):

```sh
python3 tools/icons/gen_icon.py --out iosApp/LightweightGames/Assets.xcassets/AppIcon.appiconset --sizes 1024
```

Re-run this whenever the icon design in `tools/icons/gen_icon.py` changes;
`Contents.json`'s `filename` must keep matching whatever the script writes
(`ios_AppIcon.png` for the 1024 size). Apple requires the App Store
marketing icon to have no alpha channel — the script writes 8-bit RGB
(PNG color type 2), not RGBA, so this is satisfied by construction.

## What's deliberately *not* here yet

Everything in PLAN.md §1's 15-game catalogue, sound (`Audio/Beeper.swift`),
haptics, settings, and the "unlock_all" purchase flow are implemented as of
M4/M5. What's still explicitly out of scope for the app shell itself:

- Real device/TestFlight/App Store Connect verification — see "Release
  checklist" below; everything in this directory has been written and
  statically checked on Linux but never compiled by Xcode (see "Things
  that may need fixing on first Mac build").
- Screenshots/App Store assets (`tools/screenshots/`, `docs/STORE_LISTING.md`)
  are a separate M5 deliverable, not part of this app shell.

## Things that may need fixing on first Mac build

Everything here was written against the Kotlin sources in
`core/src/commonMain/kotlin/lg/core/` and the stated Kotlin/Native → Swift
ObjC-export rules, but **none of it has been compiled**. In rough order of
"most likely to actually need a fix":

1. **`GamesCore.h` symbol names** — open the generated umbrella header
   (Xcode ▸ search for `GamesCore.h` in DerivedData, or
   `core/build/xcode-frameworks/.../GamesCore.framework/Headers/GamesCore.h`)
   and confirm against every call site in this app:
   - Objects → `.shared`: `GameRegistry.shared`.
   - Enums → lowerCamelCase static members: `PointerPhase.down/.move/.up/.cancel`,
     `Tier.free/.unlocked`, `Orientation.portrait/.landscape/.any`,
     `Align` (used only internally by the core / renderer via raw ordinals).
   - Sealed classes: not referenced from Swift in M0 (`GameStatus` /
     `Overlay` are entirely internal to `GameSession`); if a later
     milestone's UI needs to branch on them, use `is GameStatusRunning`
     style checks, not `switch`.
   - `KotlinIntRange.first` / `.last` as `Int32`: used in
     `GameViewController.preferredScreenEdgesDeferringSystemGestures`
     (`meta.players.last >= 2`).
   - Constructors with default Kotlin arguments: this app always passes
     every argument explicitly (`Scene(capacity: 1024)`,
     `session.start(players:seed:)`, `session.update(dtMs:)`, etc.) on the
     assumption default arguments aren't exported — verify at least one of
     these compiles; if Kotlin/Native *does* also generate a
     defaulted-argument convenience initializer, that's a bonus, not a
     problem.
2. **`KeyValueStore` protocol conformance** — `Storage/DefaultsStore.swift`
   and `SmokeTests.InMemoryStore` implement `getString(key:default:)`,
   `getInt(key:default:) -> Int32`, `getBoolean(key:default:) -> Bool`,
   `putString/putInt/putBoolean(key:value:)`, `remove(key:)`. If the
   generated protocol uses different external labels (e.g. `defaultValue`
   instead of `default`), every conforming type needs the same fix.
3. **`Clock` protocol** — `nowMs() -> Int64`, implemented by
   `SystemClock` and `SmokeTests.FixedClock`.
4. **`Scene` array element access** — `CoreGraphicsRenderer.swift` calls
   `scene.kind.get(index:)`, `scene.f.get(index:)`, `scene.color.get(index:)`,
   `scene.align.get(index:)`, `scene.key.get(index:)`,
   `scene.label.get(index:) as String?`. If `KotlinByteArray`/`KotlinFloatArray`/
   `KotlinIntArray`/`KotlinArray` expose a different accessor name or a
   subscript instead, this is the one file to fix; PLAN.md §5 itself flags
   this as worth profiling once it's measurable, with a `CPointer`-based
   escape hatch in `core/src/iosMain/` if per-element `.get` calls prove
   too slow for 60fps.
5. **`GameMeta.icon` / `.factory` closure calling convention** —
   `IconView.draw(_:)` calls `meta.icon(scene, size)` directly as a Swift
   closure invocation.
6. **`StringKey.ordinal` type** — `Strings.swift` calls
   `key.ordinal` and treats it as `Int32`; `Scene.key` (used for the raw
   ordinal on `TEXT` primitives) is read via `KotlinIntArray.get(index:) -> Int32`
   directly, so `Strings.string(forOrdinal:)` takes `Int32`.
7. **`NSAttributedString.size()` / text metrics** — used for centering
   TEXT/NUMBER/LABEL primitives vertically around `y` and horizontally per
   `align` (`CoreGraphicsRenderer.drawText`); this is standard UIKit, not
   Kotlin-export-dependent, but worth a visual check against `Scene`'s
   documented per-kind float layout in
   `core/src/commonMain/kotlin/lg/core/scene/Scene.kt`.
8. **ARC primitive rendering** — `Scene`'s `ARC` layout is
   `cx, cy, r, startDeg, sweepDeg, alpha` with **no** stroke-width slot.
   `CoreGraphicsRenderer.drawArc` picks an arbitrary constant
   (`arcLineWidth = 2`) and assumes `clockwise: false` (increasing angle
   sweeps counterclockwise from the CoreGraphics/UIKit +x axis). No M0 game
   emits `ARC` (only Snake is registered, and it doesn't use it), so this
   is entirely unverified against real usage — revisit once a game that
   draws arcs (e.g. a future game using clock-face style timers) lands.
9. **`FRAMEWORK_SEARCH_PATHS` / Run Script ordering** — `project.yml`
   assumes `embedAndSignAppleFrameworkForXcode`'s conventional output path
   (`core/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`) and that the
   Run Script phase XcodeGen generates from `preBuildScripts` actually runs
   *before* Compile Sources (it should, by default, for `preBuildScripts`,
   but confirm in the generated project's Build Phases tab).
10. **`.xcprivacy` / `.xcstrings` auto-inclusion** — `project.yml` includes
    the whole `LightweightGames/` folder as `sources` (excluding
    `Info.plist`, which is consumed via `info.path` instead) and relies on
    XcodeGen classifying `.xcassets`, `.xcstrings`, and `.xcprivacy` files
    as resources automatically rather than compile sources. Check the
    generated project's "Copy Bundle Resources" phase includes
    `PrivacyInfo.xcprivacy` and `Localizable.xcstrings`.

### M2 (StoreKit) additions

11. **`storeKitConfiguration` in `project.yml`** — this key on
    `schemes.LightweightGames.run` requires a recent-enough XcodeGen (added
    upstream in yonaskolb/XcodeGen#964); if the installed `xcodegen` predates
    it, the key is silently ignored (no error) and the generated scheme
    won't have a StoreKit configuration selected. Check Xcode's scheme
    editor (Run ▸ Options ▸ StoreKit Configuration) after `xcodegen
    generate`; if it's unset, either upgrade XcodeGen or set it there
    manually — see "Testing purchases" above.
12. **`EntitlementSource` enum bridging** — `Store/EntitlementReconciler.swift`
    and `StoreManager.swift` assume the Kotlin `enum class EntitlementSource`
    (`lg.core.store.Entitlement.kt`) bridges to a real, `switch`/`==`-able
    Swift enum with cases `.none`, `.play`, `.appstore` (same convention as
    `Tier`/`Orientation`/`PointerPhase`, already relied on elsewhere in this
    app). Also confirm `Entitlement(store:clock:)`'s exported initializer
    label matches (`Storage/Services.swift`), and that
    `grant(source:token:)`, `source() -> EntitlementSource`,
    `shouldRevalidate(intervalMs:) -> Bool` and `markVerified()` match
    exactly — `PRODUCT_ID` and `REVALIDATE_INTERVAL_MS` are intentionally
    *not* read from `Entitlement.companion` (hardcoded instead in
    `StoreManager.productID` / `.revalidateIntervalMs`), so a mismatch there
    would only show up as a functional bug (wrong product id / interval),
    not a compile error — double check the two hardcoded values still match
    `core/src/commonMain/kotlin/lg/core/store/Entitlement.kt`'s companion
    object if that file ever changes.
13. **`Services.store: StoreManager` as a `lazy var` of an `@MainActor`
    class, inside a plain (non-actor) `Services`** — every call site in this
    app reaches `services.store` from a UIKit context (view controller
    methods, `AppDelegate.didFinishLaunchingWithOptions`), which are
    themselves `@MainActor`-isolated in modern, concurrency-audited UIKit
    headers, so this is expected to just work under Swift 5.9's default
    (non-strict) concurrency checking. If Xcode flags a "call to main
    actor-isolated initializer in a synchronous nonisolated context" at the
    `lazy var store = StoreManager(...)` line, the fix is to mark `Services`
    itself `@MainActor` (it's already only ever touched from the main
    thread).
14. **StoreKit APIs used without being able to compile-check them** —
    `Product.products(for:)`, `Product.purchase()` /
    `Product.PurchaseResult`, `VerificationResult<Transaction>`,
    `Transaction.currentEntitlements` / `.updates` / `.finish()` /
    `.revocationDate` / `.productID` / `.id`, and `AppStore.sync()` are all
    documented iOS 15+ StoreKit 2 APIs (`StoreManager.swift`), and
    `SKTestSession(configurationFileNamed:)` / `.resetToDefaultState()` /
    `.disableDialogs` / `.clearTransactions()` are StoreKitTest APIs
    (`StoreKitTests.swift`) — all written from documentation/memory, not
    verified against an actual SDK header on this machine. If
    `clearTransactions()` turns out to be throwing (some `SKTestSession`
    methods are), add `try`.

### M5 (polish / release) additions

15. **`CoreConfig.shared`** — `lg.core.CoreConfig` (`core/src/commonMain/kotlin/lg/core/CoreConfig.kt`)
    is a plain Kotlin `object` with one `var languageTag: String` property,
    expected to bridge the same way `GameRegistry` does (`CoreConfig.shared`,
    a settable `var` on the singleton). `AppDelegate.swift` sets it once at
    launch from `Locale.preferredLanguages.first` and again on every
    `NSLocale.currentLocaleDidChangeNotification`, before any
    `GameSession`/`Game` is created — confirm the generated header exposes
    `languageTag` as mutable (not `{ get }`-only) on `CoreConfig.shared`; if
    Kotlin/Native exports a top-level `object`'s `var` as read-only from
    Swift for some reason, this needs a small `IosExports.kt` setter
    function instead (see `core/src/iosMain/kotlin/lg/core/IosExports.kt`
    for the existing pattern). Functionally this only changes which word
    list `HangmanGame.reset()` draws from (`WordsFr` vs. `WordsEn`) — a
    stale/never-set tag just means Hangman always uses English, not a
    crash — so this is low-risk but worth a quick manual check (switch the
    device/simulator language to French in Settings, launch, open Hangman,
    confirm the revealed word is French).

## Release checklist

Everything below is Mac/Xcode-only (this app shell was written and
statically checked on Linux — see the top of this file); walk it in order
before submitting a build to App Store Connect. Cross-reference
`docs/QA_CHECKLIST.md` (manual functional QA) and `PLAN.md` §7/§9/§12,
which this list summarizes for the iOS side specifically.

1. **Regenerate the project.** `cd iosApp && xcodegen generate` — pulls in
   any `project.yml` changes (this milestone: `MARKETING_VERSION 1.0.0`,
   `TARGETED_DEVICE_FAMILY 1,2`, `SUPPORTS_MACCATALYST NO`, Release
   `-Osize`/dead-code-stripping/strip/no-bitcode,
   `ASSETCATALOG_COMPILER_OPTIMIZATION space`).
2. **Tests green.** `xcodebuild test -project iosApp/LightweightGames.xcodeproj
   -scheme LightweightGames -destination 'platform=iOS Simulator,name=iPhone 15'`
   (or ⌘U) — `SmokeTests.swift` (catalogue + StringKey coverage),
   `StoreKitTests.swift`, `EntitlementReconcilerTests.swift` all pass.
   Also run `python3 iosApp/tools/check_string_keys.py` (fast, no Xcode) as
   a pre-flight before even opening Xcode.
3. **Size audit.** `tools/ios_size.sh` — archives Release, exports with App
   Thinning, and fails if the largest thinned variant exceeds the
   threshold (`MAX_MB`, default 12; PLAN.md §7 target 4–8 MB after
   thinning). Attach the resulting size report to the release, per
   `docs/QA_CHECKLIST.md`'s "rapports de taille joints à chaque release".
4. **App icon present.** Confirm
   `Assets.xcassets/AppIcon.appiconset/ios_AppIcon.png` exists (committed;
   regenerate with `python3 tools/icons/gen_icon.py --out
   iosApp/LightweightGames/Assets.xcassets/AppIcon.appiconset --sizes 1024`
   if the design changes) and that `Contents.json`'s `filename` still
   matches it.
5. **Archive & upload.** Product ▸ Archive in Xcode (Release
   configuration, `LG_USE_PREBUILT` unset so it builds `GamesCore` fresh,
   or `=1` against a known-good prebuilt framework), then Organizer ▸
   Distribute App ▸ App Store Connect ▸ Upload. `ITSAppUsesNonExemptEncryption`
   is already `false` in `Info.plist`, so App Store Connect's export
   compliance question is answered automatically (no manual encryption
   questionnaire).
6. **StoreKit product in App Store Connect.** Create the "unlock_all"
   non-consumable in App Store Connect ▸ your app ▸ In-App Purchases,
   matching `Products.storekit`'s product id, price tier, and en/fr display
   name/description exactly (see the `options` comment in `project.yml`
   and "Testing purchases" § 3 above). Submit it for review alongside the
   binary (first submission requires an IAP to be in "Waiting for Review"
   or already approved).
7. **Privacy nutrition label.** App Store Connect ▸ App Privacy ▸ answer
   "Data Not Collected" for every category — matches `PrivacyInfo.xcprivacy`
   (no `NSPrivacyCollectedDataTypes`, `NSPrivacyTracking` false) and
   `docs/STORE_LISTING.md`'s privacy claims. The only declared API usage
   reason is `UserDefaults` / `CA92.1` (local settings, scores, and
   resumable-game saves — never transmitted).
8. **Age rating: 4+.** Answer App Store Connect's age rating
   questionnaire with no mature content, no gambling, no unrestricted web
   access — everything in this app is procedurally drawn, offline, and the
   IAP is gated behind the 3-second parental-gate hold
   (`Store/ParentalGateButton.swift`). **Do not** select the Kids Category
   — PLAN.md §10 notes Apple's Kids Category has stricter IAP/third-party
   constraints this app's paywall flow doesn't target; 4+ plus the
   parental gate is the intended posture.
9. **Metadata from `docs/STORE_LISTING.md`.** Copy the app name, short/full
   descriptions, and keywords (en/fr) from that file into App Store
   Connect's listing fields; it's kept in sync with the actual 15-game
   catalogue and free/unlocked split.
10. **TestFlight pass.** Install via TestFlight (not a direct Xcode
    Release build) on a real device, run `docs/QA_CHECKLIST.md` end to
    end — airplane mode, purchase + offline relaunch, restore, rotation,
    incoming call, dark mode, 2-player multitouch without triggering
    system gestures — before releasing to the App Store.

## File map

```
iosApp/
├── project.yml                          XcodeGen spec (this file replaces PLAN.md §3's committed .xcodeproj)
├── README.md                            this file
├── Products.storekit                    local StoreKit config for "unlock_all" (Xcode scheme + SKTestSession)
├── Frameworks/                          gitignored; LG_USE_PREBUILT=1 drop-in for GamesCore.framework
├── tools/
│   └── check_string_keys.py             StringKey.kt <-> Strings.swift <-> Localizable.xcstrings ordinal/coverage check (Python, no Xcode needed)
└── LightweightGames/
    ├── AppDelegate.swift                window + Services + root UINavigationController + CoreConfig.shared.languageTag wiring
    ├── CoreConstants.swift              hand-mirrored Palette/Prim/Align/SoundId indices (see its header comment)
    ├── Strings.swift                    StringKey ordinal -> NSLocalizedString, + app_name/close/version
    ├── Localizable.xcstrings            en + fr, source language en, 44 StringKey entries + 3 shell-only extras
    ├── Info.plist                       UILaunchScreen, orientations, ITSAppUsesNonExemptEncryption, no UIApplicationSceneManifest
    ├── PrivacyInfo.xcprivacy            NSPrivacyAccessedAPICategoryUserDefaults / CA92.1, nothing collected
    ├── Assets.xcassets/                 AppIcon.appiconset (ios_AppIcon.png, 1024x1024, no alpha) + AccentColor
    ├── Catalog/
    │   ├── CatalogViewController.swift  UICollectionView, compositional layout, 2-4 columns
    │   ├── TileCell.swift               icon + title + padlock
    │   └── IconView.swift               draws GameMeta.icon via the shared renderer
    ├── Game/
    │   ├── GameViewController.swift     hosts GameView, close button, orientation, pause/persist lifecycle
    │   ├── GameView.swift               CADisplayLink, touch -> session.onPointer, draw(_:) -> renderer
    │   ├── CoreGraphicsRenderer.swift   Scene -> CGContext (the one iOS renderer, PLAN.md §2)
    │   └── Palette.swift                Palette index -> light/dark UIColor
    ├── Settings/
    │   └── SettingsViewController.swift sound/haptics switches, Restore purchases row, version
    ├── Storage/
    │   ├── DefaultsStore.swift          KeyValueStore over UserDefaults
    │   ├── SystemClock.swift            Clock over Date
    │   └── Services.swift               dependency container (kvStore, clock, settings, scores, entitlement, store, isUnlocked)
    ├── Store/
    │   ├── StoreManager.swift           StoreKit 2 façade for "unlock_all" (PLAN.md §6)
    │   ├── EntitlementReconciler.swift  pure restore()-outcome decision logic (unit-tested)
    │   ├── ParentalGateButton.swift     press-and-hold-3s control gating the buy button
    │   └── PaywallViewController.swift  price/offline/restore sheet, presented from CatalogViewController
    └── Audio/
        ├── SoundSink.swift              protocol + NoopSoundSink (M0)
        └── Haptics.swift                UIImpactFeedbackGenerator wrapper
└── LightweightGamesTests/
    ├── SmokeTests.swift                 headless Snake session + catalogue tile count
    ├── StoreKitTests.swift              SKTestSession: purchase -> unlocked, clearTransactions+restore -> locked
    └── EntitlementReconcilerTests.swift pure reconcile() decision-table tests, no StoreKit dependency
```
