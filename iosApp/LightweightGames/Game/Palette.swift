import UIKit

/// Kid-friendly, high-contrast colors for every `lg.core.scene.Palette`
/// index (see `PaletteIndex` in CoreConstants.swift), light- and
/// dark-appearance aware via `UIColor`'s dynamic-provider initializer.
enum ScenePalette {
    /// Indexed exactly like `PaletteIndex` (BG=0 ... NUMBER_8=39).
    /// `colors.count == PaletteIndex.count` is asserted by SmokeTests.
    static let colors: [UIColor] = [
        // BG
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.07, green: 0.08, blue: 0.11, alpha: 1)
                : UIColor(red: 0.97, green: 0.98, blue: 1.00, alpha: 1)
        },
        // SURFACE
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.13, green: 0.15, blue: 0.19, alpha: 1)
                : UIColor.white
        },
        // FG
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(white: 0.95, alpha: 1)
                : UIColor(white: 0.08, alpha: 1)
        },
        // FG_DIM
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(white: 0.65, alpha: 1)
                : UIColor(white: 0.40, alpha: 1)
        },
        // ACCENT
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.35, green: 0.62, blue: 1.00, alpha: 1)
                : UIColor(red: 0.16, green: 0.45, blue: 0.95, alpha: 1)
        },
        // P1
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.30, green: 0.78, blue: 0.53, alpha: 1)
                : UIColor(red: 0.13, green: 0.62, blue: 0.38, alpha: 1)
        },
        // P2
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.98, green: 0.62, blue: 0.30, alpha: 1)
                : UIColor(red: 0.90, green: 0.45, blue: 0.10, alpha: 1)
        },
        // GRID
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(white: 0.28, alpha: 1)
                : UIColor(white: 0.85, alpha: 1)
        },
        // OK
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.35, green: 0.85, blue: 0.45, alpha: 1)
                : UIColor(red: 0.20, green: 0.65, blue: 0.30, alpha: 1)
        },
        // DANGER
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.98, green: 0.40, blue: 0.40, alpha: 1)
                : UIColor(red: 0.85, green: 0.18, blue: 0.18, alpha: 1)
        },
        // TILE_0 .. TILE_11: 12 kid-friendly, mutually distinguishable hues.
        // These are fixed (not light/dark dynamic) since tiles are meant to
        // read the same regardless of theme (2048-style numbered tiles).
        UIColor(red: 0.96, green: 0.49, blue: 0.49, alpha: 1), // tile0 red
        UIColor(red: 0.98, green: 0.68, blue: 0.42, alpha: 1), // tile1 orange
        UIColor(red: 0.97, green: 0.85, blue: 0.40, alpha: 1), // tile2 yellow
        UIColor(red: 0.70, green: 0.85, blue: 0.45, alpha: 1), // tile3 lime
        UIColor(red: 0.45, green: 0.80, blue: 0.55, alpha: 1), // tile4 green
        UIColor(red: 0.40, green: 0.80, blue: 0.75, alpha: 1), // tile5 teal
        UIColor(red: 0.45, green: 0.70, blue: 0.90, alpha: 1), // tile6 sky
        UIColor(red: 0.50, green: 0.55, blue: 0.92, alpha: 1), // tile7 indigo
        UIColor(red: 0.70, green: 0.55, blue: 0.92, alpha: 1), // tile8 violet
        UIColor(red: 0.90, green: 0.55, blue: 0.85, alpha: 1), // tile9 pink
        UIColor(red: 0.80, green: 0.65, blue: 0.50, alpha: 1), // tile10 tan
        UIColor(red: 0.65, green: 0.70, blue: 0.75, alpha: 1), // tile11 slate
        UIColor(red: 0.043, green: 0.043, blue: 0.118, alpha: 1), // bezel
        UIColor(red: 0.141, green: 0.078, blue: 0.278, alpha: 1), // bezel_edge
        UIColor(red: 1.000, green: 0.180, blue: 0.592, alpha: 1), // neon_pink
        UIColor(red: 0.086, green: 0.878, blue: 1.000, alpha: 1), // neon_cyan
        UIColor(red: 1.000, green: 0.902, blue: 0.000, alpha: 1), // neon_yellow
        UIColor(red: 0.224, green: 1.000, blue: 0.078, alpha: 1), // neon_green
        UIColor(red: 1.000, green: 0.541, blue: 0.122, alpha: 1), // neon_orange
        UIColor(red: 1.000, green: 0.231, blue: 0.231, alpha: 1), // neon_red
        UIColor(red: 0.957, green: 0.957, blue: 1.000, alpha: 1), // neon_white
        UIColor(red: 0.608, green: 0.361, blue: 1.000, alpha: 1), // neon_purple
        // number1
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.431, green: 0.659, blue: 1.000, alpha: 1)
                : UIColor(red: 0.102, green: 0.310, blue: 0.812, alpha: 1)
        },
        // number2
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.357, green: 0.824, blue: 0.486, alpha: 1)
                : UIColor(red: 0.075, green: 0.416, blue: 0.173, alpha: 1)
        },
        // number3
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 1.000, green: 0.482, blue: 0.447, alpha: 1)
                : UIColor(red: 0.678, green: 0.106, blue: 0.106, alpha: 1)
        },
        // number4
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.718, green: 0.612, blue: 1.000, alpha: 1)
                : UIColor(red: 0.416, green: 0.184, blue: 0.627, alpha: 1)
        },
        // number5
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.878, green: 0.639, blue: 0.420, alpha: 1)
                : UIColor(red: 0.549, green: 0.227, blue: 0.071, alpha: 1)
        },
        // number6
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.310, green: 0.839, blue: 0.816, alpha: 1)
                : UIColor(red: 0.024, green: 0.384, blue: 0.424, alpha: 1)
        },
        // number7
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.953, green: 0.953, blue: 0.980, alpha: 1)
                : UIColor(red: 0.125, green: 0.125, blue: 0.173, alpha: 1)
        },
        // number8
        UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.659, green: 0.659, blue: 0.729, alpha: 1)
                : UIColor(red: 0.353, green: 0.353, blue: 0.416, alpha: 1)
        },
    ]

    /// Resolves `colors` against `traitCollection` (light/dark) into a flat
    /// `CGColor` table, indexed exactly like `colors` / `PaletteIndex`.
    /// Call again from `traitCollectionDidChange` — never per frame.
    static func cgColors(for traitCollection: UITraitCollection) -> [CGColor] {
        colors.map { $0.resolvedColor(with: traitCollection).cgColor }
    }
}
