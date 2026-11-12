import Foundation

/// Hand-mirrored copies of the small integer/const tables `lg.core.scene`
/// exposes as Kotlin `object`s. Kotlin `const val`s inside an `object` are
/// exported to Swift as properties on the `.shared` singleton (e.g.
/// `Palette.shared.BG`), but we deliberately hardcode the values here
/// instead of calling into `GamesCore` for them: these are plain integers
/// that never change at runtime, and hardcoding removes any doubt about how
/// `object` constants round-trip through the Objective-C bridge.
///
/// **Keep these in sync by hand** with:
/// - `core/src/commonMain/kotlin/lg/core/scene/Palette.kt`
/// - `core/src/commonMain/kotlin/lg/core/scene/Prim.kt`
/// - `core/src/commonMain/kotlin/lg/core/scene/Align.kt`
/// - `core/src/commonMain/kotlin/lg/core/scene/SoundId.kt`

/// Mirrors `lg.core.scene.Palette`. Index into `ScenePalette.colors`
/// (Palette.swift), never a raw color.
enum PaletteIndex {
    static let bg = 0
    static let surface = 1
    static let fg = 2
    static let fgDim = 3
    static let accent = 4
    static let p1 = 5
    static let p2 = 6
    static let grid = 7
    static let ok = 8
    static let danger = 9
    static let tile0 = 10
    static let tile1 = 11
    static let tile2 = 12
    static let tile3 = 13
    static let tile4 = 14
    static let tile5 = 15
    static let tile6 = 16
    static let tile7 = 17
    static let tile8 = 18
    static let tile9 = 19
    static let tile10 = 20
    static let tile11 = 21
    static let bezel = 22
    static let bezelEdge = 23
    static let neonPink = 24
    static let neonCyan = 25
    static let neonYellow = 26
    static let neonGreen = 27
    static let neonOrange = 28
    static let neonRed = 29
    static let neonWhite = 30
    static let neonPurple = 31
    static let number1 = 32
    static let number2 = 33
    static let number3 = 34
    static let number4 = 35
    static let number5 = 36
    static let number6 = 37
    static let number7 = 38
    static let number8 = 39

    /// Total number of palette entries; `ScenePalette.colors.count` must equal this.
    static let count = 40
}

/// Mirrors `lg.core.scene.Prim` (values stored in `Scene.kind`, a `ByteArray`
/// bridged to Swift as `KotlinByteArray`, elements read as `Int8`).
enum PrimKind {
    static let rect: Int8 = 0
    static let circle: Int8 = 1
    static let line: Int8 = 2
    static let text: Int8 = 3
    static let number: Int8 = 4
    static let label: Int8 = 5
    static let arc: Int8 = 6
}

/// Mirrors `lg.core.scene.Align` ordinal values (stored in `Scene.align`, a
/// `ByteArray` read as `Int8`).
enum AlignKind {
    static let left: Int8 = 0
    static let center: Int8 = 1
    static let right: Int8 = 2
}

/// Mirrors `lg.core.scene.SoundId` bit masks (OR-combined into
/// `Scene.soundMask`, an `Int` bridged to Swift as `Int32`).
enum SoundBit {
    static let tick: Int32 = 1 << 0
    static let eat: Int32 = 1 << 1
    static let hit: Int32 = 1 << 2
    static let win: Int32 = 1 << 3
    static let lose: Int32 = 1 << 4
    static let explosion: Int32 = 1 << 6
}
