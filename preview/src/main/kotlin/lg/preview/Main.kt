package lg.preview

// Dev-only tool; see Java2DRenderer.kt header. CLI entry point: renders every
// (or a chosen subset of) registered game to a handful of PNGs so rendering
// can be checked on a headless Linux box without any device. Never shipped.

import lg.core.GameMeta
import lg.core.GameRegistry
import lg.core.input.PointerPhase
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.session.GameSession
import lg.core.store.Scores
import lg.core.store.Settings
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private class Options(
    val outDir: File,
    val width: Float,
    val height: Float,
    val scale: Float,
    val dark: Boolean,
    val gameIds: List<String>?,
)

private fun parseArgs(args: Array<String>): Options {
    var out = "preview/out"
    var portraitW = 390f
    var portraitH = 844f
    var landscape = false
    var scale = 2f
    var dark = false
    var games: List<String>? = null

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--out" -> {
                out = args[++i]
            }
            "--size" -> {
                val spec = args[++i]
                val parts = spec.split("x", "X")
                require(parts.size == 2) { "invalid --size '$spec', expected WxH" }
                portraitW = parts[0].toFloat()
                portraitH = parts[1].toFloat()
            }
            "--landscape" -> landscape = true
            "--scale" -> scale = args[++i].toFloat()
            "--games" -> games = args[++i].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            "--dark" -> dark = true
            else -> System.err.println("preview: ignoring unknown argument '$arg'")
        }
        i++
    }

    val w = if (landscape) maxOf(portraitW, portraitH) else portraitW
    val h = if (landscape) minOf(portraitW, portraitH) else portraitH
    return Options(File(out), w, h, scale, dark, games)
}

fun main(args: Array<String>) {
    val opts = parseArgs(args)
    opts.outDir.mkdirs()

    val metas = if (opts.gameIds != null) {
        opts.gameIds.mapNotNull { id ->
            val meta = GameRegistry.byId[id]
            if (meta == null) System.err.println("preview: unknown game id '$id', skipping")
            meta
        }
    } else {
        GameRegistry.all
    }

    val strings = PreviewStrings.load()
    val written = mutableListOf<File>()

    for (meta in metas) {
        written += renderGame(meta, opts, strings)
    }

    written += renderCatalog(metas, opts, strings)

    println("Wrote ${written.size} files to ${opts.outDir.absolutePath}:")
    for (f in written.sortedBy { it.name }) {
        println("  ${f.name}  (${f.length()} bytes)")
    }
}

private fun renderGame(meta: GameMeta, opts: Options, strings: PreviewStrings): List<File> {
    val out = mutableListOf<File>()
    val store = InMemoryStore()
    val clock = ManualClock()
    val scores = Scores(store)
    val settings = Settings(store)
    val session = GameSession(meta, scores, settings, clock)
    session.resize(opts.width, opts.height)

    val renderer = Java2DRenderer(opts.width, opts.height, opts.scale, opts.dark, strings)
    val scene = Scene()

    // 0: the Start overlay, before any input.
    scene.clear()
    session.render(scene)
    out += writePng(renderer.render(scene), opts.outDir, "${meta.id}_0_start.png")

    // Start the session, then replay a small generic input script while
    // advancing the clock frame by frame (16 ms, ~60 fps).
    session.start(players = meta.players.first, seed = 42)

    val cx = opts.width / 2f
    val cy = opts.height / 2f
    var tapDone = false
    var swipeDone = false
    var dragStarted = false
    var dragDone = false
    var capturedPlay = false
    var capturedMid = false
    val dragStartX = opts.width * 0.25f
    val dragEndX = opts.width * 0.75f

    var elapsed = 0
    val dtMs = 16
    while (elapsed <= 4000) {
        if (!tapDone && elapsed >= 300) {
            session.onPointer(1, PointerPhase.DOWN, cx, cy)
            session.onPointer(1, PointerPhase.UP, cx, cy)
            tapDone = true
        }
        if (!swipeDone && elapsed >= 600) {
            session.onPointer(2, PointerPhase.DOWN, cx - 50f, cy)
            session.onPointer(2, PointerPhase.MOVE, cx + 50f, cy)
            session.onPointer(2, PointerPhase.UP, cx + 50f, cy)
            swipeDone = true
        }
        if (!dragStarted && elapsed >= 900) {
            session.onPointer(3, PointerPhase.DOWN, dragStartX, cy)
            dragStarted = true
        }
        if (dragStarted && !dragDone && elapsed in 900..1200) {
            val t = ((elapsed - 900) / 300f).coerceIn(0f, 1f)
            val x = dragStartX + (dragEndX - dragStartX) * t
            session.onPointer(3, PointerPhase.MOVE, x, cy)
        }
        if (!dragDone && elapsed >= 1200) {
            session.onPointer(3, PointerPhase.UP, dragEndX, cy)
            dragDone = true
        }

        clock.advance(dtMs.toLong())
        session.update(dtMs)
        elapsed += dtMs

        if (!capturedPlay && elapsed >= 1500) {
            scene.clear()
            session.render(scene)
            out += writePng(renderer.render(scene), opts.outDir, "${meta.id}_1_play.png")
            capturedPlay = true
        }
        if (!capturedMid && elapsed >= 4000) {
            scene.clear()
            session.render(scene)
            out += writePng(renderer.render(scene), opts.outDir, "${meta.id}_2_mid.png")
            capturedMid = true
        }
    }

    out += writeIcon(meta, opts, strings)
    return out
}

private const val ICON_SIZE_PT = 256f

private fun writeIcon(meta: GameMeta, opts: Options, strings: PreviewStrings): File {
    val scene = Scene()
    scene.rect(0f, 0f, ICON_SIZE_PT, ICON_SIZE_PT, Palette.SURFACE, radius = ICON_SIZE_PT * 0.18f)
    meta.icon(scene, ICON_SIZE_PT)
    val renderer = Java2DRenderer(ICON_SIZE_PT, ICON_SIZE_PT, opts.scale, opts.dark, strings, paintBackground = false)
    return writePng(renderer.render(scene), opts.outDir, "${meta.id}_3_icon.png")
}

/** A mock catalog grid (2 columns, 390pt wide) of every previewed game's icon + title. */
private fun renderCatalog(metas: List<GameMeta>, opts: Options, strings: PreviewStrings): File {
    val cols = 2
    val gridWidthPt = 390f
    val tileWidthPt = gridWidthPt / cols
    val iconPt = 96f
    val paddingPt = 12f
    val titleHeightPt = 28f
    val tileHeightPt = paddingPt + iconPt + 6f + titleHeightPt + paddingPt
    val rows = (metas.size + cols - 1) / cols
    val gridHeightPt = rows * tileHeightPt

    val colors = PreviewPalette.colorsFor(opts.dark)
    val scale = opts.scale
    val pxW = (gridWidthPt * scale).toInt().coerceAtLeast(1)
    val pxH = (gridHeightPt * scale).toInt().coerceAtLeast(1)
    val image = BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.scale(scale.toDouble(), scale.toDouble())

        g.color = Color(colors[Palette.BG] and 0xFFFFFF)
        g.fillRect(0, 0, gridWidthPt.toInt() + 1, gridHeightPt.toInt() + 1)

        val titleFont = Font("SansSerif", Font.BOLD, 15)
        for ((index, meta) in metas.withIndex()) {
            val col = index % cols
            val row = index / cols
            val tx = col * tileWidthPt
            val ty = row * tileHeightPt

            g.color = Color(colors[Palette.SURFACE] and 0xFFFFFF)
            g.fill(
                RoundRectangle2D.Float(
                    tx + paddingPt / 2f,
                    ty + paddingPt / 2f,
                    tileWidthPt - paddingPt,
                    tileHeightPt - paddingPt,
                    16f,
                    16f,
                ),
            )

            val iconScene = Scene()
            meta.icon(iconScene, iconPt)
            val iconRenderer = Java2DRenderer(iconPt, iconPt, scale, opts.dark, strings, paintBackground = false)
            val iconImage = iconRenderer.render(iconScene)
            val iconX = tx + (tileWidthPt - iconPt) / 2f
            val iconY = ty + paddingPt
            // `g` already has `scale(scale, scale)` applied above, so the
            // destination rect is given in the same point units as
            // everything else drawn on it (do NOT premultiply by `scale`
            // here, or icons end up drawn at scale^2 size/offset).
            g.drawImage(
                iconImage,
                iconX.toInt(),
                iconY.toInt(),
                iconPt.toInt(),
                iconPt.toInt(),
                null,
            )

            g.font = titleFont
            g.color = Color(colors[Palette.FG] and 0xFFFFFF)
            val title = strings.get(meta.titleKey.ordinal)
            val fm = g.fontMetrics
            val titleX = tx + (tileWidthPt - fm.stringWidth(title)) / 2f
            val titleY = iconY + iconPt + 6f + fm.ascent
            g.drawString(title, titleX, titleY)
        }
    } finally {
        g.dispose()
    }
    return writePng(image, opts.outDir, "catalog.png")
}

private fun writePng(image: BufferedImage, dir: File, name: String): File {
    val file = File(dir, name)
    ImageIO.write(image, "png", file)
    return file
}
