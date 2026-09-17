# preview

Dev-only headless PNG preview tool. **Never shipped** — it exists only so
every game's rendering can be checked on a Linux dev box with no
Android/iOS device.

It builds an in-memory `GameSession` per registered game, renders the Start
overlay, replays a small generic input script (tap, swipe, drag) while
advancing a fake `Clock`, and rasterizes the resulting `Scene`s with Java2D
(`Java2DRenderer.kt`), mirroring `androidApp`'s `CanvasRenderer`/`Palette`
conventions. Zero external dependencies (JDK only).

```sh
source tools/env.sh
./gradlew :preview:run --args="--out preview/out"
```

Flags: `--out DIR`, `--size WxH`, `--landscape`, `--scale N`, `--games id,id`,
`--dark`. Output lands in `preview/out/` (gitignored): `<id>_0_start.png`,
`<id>_1_play.png`, `<id>_2_mid.png`, `<id>_3_icon.png` per game, plus a
`catalog.png` grid of every icon.
