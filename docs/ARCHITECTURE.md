# Architecture

This document is a short orientation for the `core` module, built starting in
milestone M0(a) and extended in M1(a). See `PLAN.md` for the full product and
delivery plan (in French); this file only covers what is needed to build the
project and add a new game.

## Building

```sh
source tools/env.sh   # pins JAVA_HOME to a local JDK 21 and exports ANDROID_HOME
./gradlew :core:jvmTest                  # run all commonTest tests on the JVM (fast, no Android SDK needed)
./gradlew :core:compileKotlinIosArm64    # sanity-compile the iOS Kotlin/Native target (needs network the first time)
```

`tools/env.sh` exists because the build machine only ships a bare JRE; it
downloads and points to a real JDK 21 (Temurin) instead. `ANDROID_HOME` is
exported for when the Android SDK is present, but `core/build.gradle.kts`
never requires it: the Android library target (`androidLibrary { ... }`,
via the `com.android.kotlin.multiplatform.library` plugin) is only applied
when a real SDK directory is found (via `ANDROID_HOME` or
`local.properties`'s `sdk.dir`). Without it, `:core:jvmTest` and the iOS
targets still configure and run normally.

The `jvm()` target exists **only** to run `commonTest` quickly on any
machine (including this Linux dev box, which cannot run Android
instrumentation tests or iOS XCTest). It is never shipped; the actual
products are the Android library (consumed by `androidApp`, added later) and
the static `GamesCore.xcframework` built from `iosArm64` +
`iosSimulatorArm64` (consumed by `iosApp`, added later).

## The `Game` / `Scene` / `GameSession` contract

- **`Game`** (`lg.core.Game`) is the interface every mini-game implements:
  `reset`, `resize`, `onPointer`, `onGesture`, `update(dtMs): Boolean`,
  `render(scene)`, plus optional `snapshot`/`restore` for resumable games.
  A game never draws menus, HUD or overlays — only its own play area.
- **`Scene`** (`lg.core.scene.Scene`) is a fixed-capacity, structure-of-arrays
  draw list. Games call `rect`/`circle`/`line`/`arc`/`text`/`number`/`label`
  during `render`; nothing allocates. Colors are `Palette` indices (never
  ARGB) so the platform can remap them for light/dark themes. Capacity
  overflow increments `dropped` instead of throwing — tests assert it stays
  at 0.
- **`GameSession`** (`lg.core.session.GameSession`) is the only place that
  owns a `Game` instance end-to-end: it routes raw pointer events either to
  the game or to the currently shown `Overlay` (Start / Paused / Over), runs
  a `GestureDetector` to turn pointer streams into taps/swipes/long-presses,
  clamps `dtMs` to 50 ms before calling `Game.update`, draws the game then
  the score/best HUD then the overlay on `render`, and persists best scores
  (`Scores`) and, for resumable games, save data (`KeyValueStore`, key
  `"save.<id>"`).

**Fixed play zone.** Whatever the screen, a game is always told the same size
through `Game.resize`: `GameSession.LOGICAL_SHORT x LOGICAL_LONG` (360 x 560 pt)
in portrait, or 560 x 360 for a game declared `Orientation.LANDSCAPE` (only
Paddles). The session scales that zone uniformly to fit the screen (centred; in
portrait above the Help/Quit bar, in landscape beside a Help/Quit column), maps
pointers and gestures back into zone coordinates, and applies the scale to the
drawn `Scene` (`Scene.transformFrom`). So the rules, speeds and board sizes of a
game never depend on the device; only the zoom does. Games must not assume any
other size, and must keep lengths in points.

The session also notices a game that ends during an input (a last move made by a
tap) and not only during `update`, so the result overlay always appears.

Platforms only ever talk to `GameSession`, never to a `Game` directly.

## Adding a new game

1. Create `core/src/commonMain/kotlin/lg/core/games/<id>/<Name>Game.kt`
   implementing `Game`. Keep `update`/`render`/`onPointer`/`onGesture`
   allocation-free: preallocate arrays/fields in the class body, reuse them
   every frame, and only use `scene.label(...)` with string literals (never
   string concatenation). `snapshot()`/`restore()` are the one exception —
   they only run from `GameSession.persist()` on pause, never per-frame, so
   building a `Map`/`String` there is fine; document that in the function's
   KDoc so it doesn't get "optimized" into a zero-alloc shape later.
2. By convention, reserve a `HUD_TOP_PT = 32f` constant and start your play
   area's `y` there (e.g. via `Layout.fitSquare(w, h, HUD_TOP_PT, rect)` for
   a square board, or by drawing directly below that offset for a
   full-width playfield). This matches the HUD strip `GameSession` draws on
   top (`score` / `best`), so the two never overlap; see Snake, `Game2048`
   and `BreakoutGame` for the two usage patterns.
3. Give it a `companion object` with a `META: GameMeta` describing its id,
   title key, tier, player range, orientation, complexity, resumability, a
   `factory` lambda, and an `icon` lambda that draws a small representative
   tile icon into a `Scene`.
4. Register it in `lg.core.GameRegistry.all` (free games first).
5. Add `StringKey` entries for any new user-facing text (no translations in
   `core` — platforms map the enum ordinal to a localized string resource).
6. Write `games/<id>/<Name>RulesTest` covering the game's key rules with a
   seeded `Rng`, plus let it participate automatically in
   `fuzz/HeadlessPlayTest` and `fuzz/DeterminismTest` (both iterate over
   every entry in `GameRegistry.all`, and already send randomized
   `DOWN`/`MOVE`/`UP`/`CANCEL` pointer streams that exercise drag-style
   input across a handful of reused pointer ids, so a paddle/drag-driven
   game like Breakout is covered without a dedicated code path). Expose the
   minimal state a rules test needs via `internal fun debugXxx()` accessors
   (see `SnakeGame.debugPlaceFood`, `Game2048.debugSetCell`,
   `BreakoutGame.debugSetBall`) rather than widening the public `Game`
   contract.

### Resumable games (`resumable = true`)

If (and only if) a game can be meaningfully resumed after the app is
backgrounded, override `snapshot()`/`restore()`:

- Encode every field needed to continue deterministically — not just
  visible state, but also any `Rng` in use. `Rng` exposes its `state: Long`
  and a `Rng.fromState(state)` factory precisely so a save can capture
  "where the random sequence currently is" and continue it exactly, instead
  of restarting the sequence from the original seed (see `Game2048`).
- Use `KvCodec.encode`/`decode` for the `"k=v;k=v"` string; it already
  escapes `=`, `;` and `\`.
- `restore()` must validate every field before mutating any state, and
  return `false` without touching the game if anything is missing or
  malformed (`toIntOrNull`/`toLongOrNull`, range checks, required-key
  checks) — a corrupted or hand-edited save must never crash or leave the
  game half-restored. `fuzz/HeadlessPlayTest` calls `snapshot()`/`restore()`
  at arbitrary points during play for every resumable game, so this must
  hold regardless of when it happens.
- Purely visual/transient state (in-flight tile-slide animation, a queued
  input) does not need to survive a restore; resetting it to "idle" is
  fine and keeps the encoding small.

### Sound / haptic events

A game's `render()` sets `scene.sound(...)`/`scene.haptic()` from flags
that describe "what just happened" (e.g. Snake's `ateThisTick`), the same
way across every frame those flags stay true — `GameSession`, not the
game, is responsible for making sure the platform only hears about it
once: it tracks whether a real state change happened since the last
`render()` (`update()` returning `true`, `start()`, or a transition to
`Over`) and mutes `soundMask`/`haptic` on any render that isn't the first
one after such a change. A game should not try to reset its own flags to
achieve this; it only needs to keep them readable across renders, exactly
like Snake already does.

## AI helper

`lg.core.ai.Minimax` (`core/src/commonMain/kotlin/lg/core/ai/Minimax.kt`) is
a small, generic, allocation-free negamax search with alpha-beta pruning
over an abstract `MinimaxBoard` (`moveCount`/`move`/`apply`/`undo`/
`terminalScore`/`heuristic`). A game exposes its own board state to the
search by implementing `MinimaxBoard` against its own arrays (see
`TicTacToeGame.SearchBoard` and `FourInRowGame.SearchBoard`, both private
inner classes that mutate/restore the game's real `IntArray`s in place, so a
search allocates nothing): `apply`/`undo` are always called in perfectly
nested pairs, so a single mutable board representation can be shared for the
whole search tree. `Minimax.bestMove(board, player, opponent, maxDepth)`
returns the chosen move id. `TicTacToeGame` searches to full depth (9, i.e.
perfect play on a 3x3 board); `FourInRowGame` searches to depth 4 with a
heuristic (center-column preference plus a count of "open" 2/3-in-a-row
windows with no opposing disc in them). Both games run the search from
`update()`, gated behind a short visible delay (`wantsFrames` true only
while waiting), so the computer's move never appears instantaneous.

## Headless preview tool (`preview/`)

`preview/` is a dev-only Gradle module (plain `org.jetbrains.kotlin.jvm`
plugin, depending on `:core`'s `jvm()` target) that renders every registered
game's `Scene` to PNGs with Java2D, so rendering can be checked on this
headless Linux box without an Android/iOS device. **It is never shipped.**
Run `source tools/env.sh && ./gradlew :preview:run --args="--out preview/out"`;
see `preview/README.md` for flags. It intentionally does not modify `core/`,
`androidApp/`, or `iosApp/`; its Java2D renderer and palette are a hand-kept
mirror of `androidApp`'s `CanvasRenderer`/`Palette`.

## `CoreConfig`

`lg.core.CoreConfig` (`core/src/commonMain/kotlin/lg/core/CoreConfig.kt`) is
a tiny mutable, process-wide object the platform sets once at startup —
currently just `languageTag: String` (default `"en"`), a BCP-47-ish locale
tag such as `"en"` or `"fr-FR"`. It exists purely so `HangmanGame` can pick
between `WordsFr.WORDS` and `WordsEn.WORDS` (the tag starting with `"fr"`,
case-insensitively, selects French) without threading a language parameter
through every `Game`/`GameSession` constructor. No other game reads it.

## Sudoku / Nonogram generation

Both `SudokuGame` and `NonogramGame` need a size/difficulty choice before
they can generate a board, but `GameSession` only ever calls `Game.reset`
once, from its own generic Start overlay, with no way to ask the game for a
parameter first. So `reset` puts both games into a `choosing` state
(`wantsFrames = false`) where the game itself draws three big EASY/MEDIUM/
HARD buttons and `onGesture` hit-tests them; picking one calls the generator
and switches to the normal board screen. This keeps `GameSession`/`Overlay`
completely generic — see `SudokuGame`/`NonogramGame`'s class KDoc.

- `SudokuGenerator` (`games/sudoku/SudokuGenerator.kt`) fills a full grid via
  randomized backtracking, then removes cells while a bounded backtracking
  solver (`SolutionCounter`, capped at 2 solutions) confirms the puzzle stays
  uniquely solvable, down to ~36/30/26 givens for EASY/MEDIUM/HARD. A shared
  step budget (150,000 solver steps per generation) bounds worst-case time;
  if exhausted, removal simply stops early, keeping whatever givens are
  already safely removed (a slightly easier puzzle than requested, but still
  valid and unique) — see its class KDoc. Measured on this dev machine (JVM):
  5 HARD (26-given) generations complete in ~55ms total (~11ms each), far
  under the PLAN.md §10 target of ~200ms per puzzle and the test suite's
  generous 1.5s-per-batch bound (`SudokuRulesTest.generationStaysWellUnderTheGenerousTestBound`).
- `NonogramGenerator` (`games/nonogram/NonogramGenerator.kt`) fills a random
  picture (~55% density) at 5x5/10x10/15x15 (EASY/MEDIUM/HARD), computes its
  row/column clues, then verifies it is solvable by pure line logic alone
  using `LineSolver` (`games/nonogram/LineSolver.kt`): repeatedly applies
  single-line constraint propagation (enumerating every placement of a
  line's blocks consistent with what's already known, via a shared
  placement budget so a single hard line can never hang generation) to every
  row and column until either the grid is fully determined or no line makes
  further progress. If unsolvable by logic alone, it regenerates a new
  random picture, up to 20 attempts; if none verify, the last picture is
  accepted anyway (`Puzzle.solvableByLogic` records which happened). Measured
  on this dev machine (JVM): 5 HARD (15x15) generations complete in ~70ms
  total (~14ms each) (`NonogramRulesTest.generationStaysWellUnderTheGenerousTestBound`).

## Current status (M4)

All 3 FREE games (`snake`, `g2048`, `breakout`, M1 part a) plus all 12
UNLOCKED games are registered, in this order: `sequence`, `pairs`, `sliding`,
`tictactoe`, `paddles`, `fourinrow` (M3, complexity S and Four in a Row at
M), then `bird`, `mines`, `hangman`, `reversi`, `sudoku`, `nonogram` (M4,
complexity M/C) — `lg.core.games.{sequence,pairs,sliding,tictactoe,paddles,
fourinrow,bird,mines,hangman,reversi,sudoku,nonogram}`. 15 games total, the
full v1 catalogue from PLAN.md §1. Android app (`androidApp/`) and iOS app
(`iosApp/`) shells are separate, concurrently developed sub-tasks and are not
part of this module; the new titles' platform strings are added by a later
task, so `androidApp`/`iosApp` were not touched here.
