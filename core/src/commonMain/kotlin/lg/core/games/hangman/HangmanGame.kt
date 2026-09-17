package lg.core.games.hangman

import lg.core.Complexity
import lg.core.CoreConfig
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Align
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.Rng

/**
 * Classic hangman: guess a hidden word one letter at a time via an
 * on-screen keyboard, with [MAX_WRONG] wrong guesses allowed before the game
 * is lost.
 *
 * ### Word source
 *
 * [reset] picks one word uniformly at random (via [Rng]) from [WordsFr.WORDS]
 * when [CoreConfig.languageTag] starts with `"fr"` (case-insensitive), or
 * [WordsEn.WORDS] otherwise. Every word is lowercase a-z only (see the
 * generator, `tools/wordlist/build_wordlist.py`), so letter matching is
 * plain `char - 'a'` indexing with no locale-sensitive comparison.
 *
 * ### Rendering without string concatenation
 *
 * [LETTERS] holds the 26 single-letter strings `"a".."z"` as literals, built
 * once; both the word display and the keyboard draw from it via
 * `scene.label`, so `render` never concatenates strings.
 *
 * ### Score
 *
 * This game is not resumable and a `GameSession` restart always calls
 * [reset] again (a fresh word, [score] back to 0), so there is no
 * meaningful in-instance "words solved in a row" counter to carry across
 * restarts. [score] is simply `1` on a win, `0` on a loss/still playing.
 */
public class HangmanGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean = false

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f

    private var rng: Rng = Rng(0L)
    private var word: String = ""
    private val guessed = BooleanArray(26)
    private var wrongCount: Int = 0

    // Layout, recomputed on resize().
    private var gallowsX: Float = 0f
    private var gallowsY: Float = 0f
    private var gallowsW: Float = 0f
    private var gallowsH: Float = 0f
    private var wordY: Float = 0f
    private var keyAreaX: Float = 0f
    private var keyAreaY: Float = 0f
    private var keyW: Float = 0f
    private var keyH: Float = 0f

    // Events from the most recent action, read (not mutated) by render().
    private var correctThisMove: Boolean = false
    private var wrongThisMove: Boolean = false
    private var wonThisMove: Boolean = false
    private var lostThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        val words = if (CoreConfig.languageTag.lowercase().startsWith("fr")) WordsFr.WORDS else WordsEn.WORDS
        word = words[rng.nextInt(words.size)]
        guessed.fill(false)
        wrongCount = 0
        score = 0
        correctThisMove = false
        wrongThisMove = false
        wonThisMove = false
        lostThisMove = false
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height

        val gap = KEY_GAP_PT
        keyW = ((viewWidth - (KEY_COLS + 1) * gap) / KEY_COLS).coerceAtLeast(1f)
        keyH = KEY_HEIGHT_PT
        val keyboardH = KEY_ROWS * keyH + (KEY_ROWS + 1) * gap
        keyAreaX = (viewWidth - KEY_COLS * keyW - (KEY_COLS - 1) * gap) / 2f
        keyAreaY = (viewHeight - keyboardH).coerceAtLeast(HUD_TOP_PT + gallowsMinH())
        keyAreaY += gap

        wordY = keyAreaY - gap - WORD_AREA_H

        // The figure area is capped to a friendly aspect ratio and centred in the
        // space between the HUD and the word, instead of stretching to fill a
        // tall phone screen.
        val areaTop = HUD_TOP_PT + GALLOWS_TOP_MARGIN_PT
        val areaH = (wordY - WORD_AREA_H - areaTop).coerceAtLeast(40f)
        gallowsW = viewWidth * 0.7f
        gallowsH = minOf(areaH, gallowsW * 1.1f)
        gallowsX = (viewWidth - gallowsW) / 2f
        gallowsY = areaTop + (areaH - gallowsH) / 2f
    }

    private fun gallowsMinH(): Float = 100f

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Hangman is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        correctThisMove = false
        wrongThisMove = false
        wonThisMove = false
        lostThisMove = false
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return
        val idx = keyAt(gesture.x, gesture.y) ?: return
        guessLetter(idx)
    }

    override fun update(dtMs: Int): Boolean {
        correctThisMove = false
        wrongThisMove = false
        wonThisMove = false
        lostThisMove = false
        return false
    }

    private fun keyAt(x: Float, y: Float): Int? {
        if (keyW <= 0f) return null
        if (x < keyAreaX || x > keyAreaX + KEY_COLS * keyW) return null
        if (y < keyAreaY || y > keyAreaY + KEY_ROWS * keyH) return null
        val col = ((x - keyAreaX) / keyW).toInt().coerceIn(0, KEY_COLS - 1)
        val row = ((y - keyAreaY) / keyH).toInt().coerceIn(0, KEY_ROWS - 1)
        val idx = row * KEY_COLS + col
        return if (idx in 0 until 26) idx else null
    }

    private fun guessLetter(letterIdx: Int) {
        if (guessed[letterIdx]) return
        guessed[letterIdx] = true
        val ch = 'a' + letterIdx
        var hit = false
        for (c in word) if (c == ch) hit = true
        if (hit) {
            correctThisMove = true
            if (isWordFullyRevealed()) {
                status = GameStatus.Over(0)
                score = 1
                wonThisMove = true
            }
        } else {
            wrongCount++
            wrongThisMove = true
            if (wrongCount >= MAX_WRONG) {
                status = GameStatus.Over(0)
                lostThisMove = true
            }
        }
    }

    private fun isWordFullyRevealed(): Boolean {
        for (c in word) if (!guessed[c - 'a']) return false
        return true
    }

    // --- Test-only accessors (internal: exercised by HangmanRulesTest) ----

    internal fun debugWord(): String = word
    internal fun debugGuessed(letterIdx: Int): Boolean = guessed[letterIdx]
    internal fun debugWrongCount(): Int = wrongCount
    internal fun debugGuessLetter(letterIdx: Int): Unit = guessLetter(letterIdx)

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)

        drawGallows(scene)
        drawWord(scene)
        drawKeyboard(scene)

        if (correctThisMove) scene.sound(SoundId.EAT)
        if (wrongThisMove) scene.sound(SoundId.HIT)
        if (wonThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
        if (lostThisMove) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    private fun drawGallows(scene: Scene) {
        val baseY = gallowsY + gallowsH
        val poleX = gallowsX + gallowsW * 0.2f
        val topY = gallowsY
        val barEndX = gallowsX + gallowsW * 0.75f
        val ropeEndY = topY + gallowsH * 0.12f

        scene.line(poleX - gallowsW * 0.15f, baseY, poleX + gallowsW * 0.15f, baseY, Palette.FG_DIM, width = 4f)
        scene.line(poleX, baseY, poleX, topY, Palette.FG_DIM, width = 4f)
        scene.line(poleX, topY, barEndX, topY, Palette.FG_DIM, width = 4f)
        scene.line(barEndX, topY, barEndX, ropeEndY, Palette.FG_DIM, width = 3f)

        val headR = gallowsH * 0.1f
        val headCx = barEndX
        val headCy = ropeEndY + headR
        val bodyTopY = headCy + headR
        val bodyBottomY = bodyTopY + gallowsH * 0.28f

        if (wrongCount >= 1) scene.circle(headCx, headCy, headR, Palette.ACCENT)
        if (wrongCount >= 2) scene.line(headCx, bodyTopY, headCx, bodyBottomY, Palette.ACCENT, width = 4f)
        if (wrongCount >= 3) scene.line(headCx, bodyTopY + gallowsH * 0.06f, headCx - gallowsW * 0.12f, bodyTopY + gallowsH * 0.16f, Palette.ACCENT, width = 3f)
        if (wrongCount >= 4) scene.line(headCx, bodyTopY + gallowsH * 0.06f, headCx + gallowsW * 0.12f, bodyTopY + gallowsH * 0.16f, Palette.ACCENT, width = 3f)
        if (wrongCount >= 5) scene.line(headCx, bodyBottomY, headCx - gallowsW * 0.12f, bodyBottomY + gallowsH * 0.16f, Palette.ACCENT, width = 3f)
        if (wrongCount >= 6) scene.line(headCx, bodyBottomY, headCx + gallowsW * 0.12f, bodyBottomY + gallowsH * 0.16f, Palette.ACCENT, width = 3f)
        if (wrongCount >= 7) scene.arc(headCx, headCy + headR * 0.15f, headR * 0.5f, 20f, 140f, Palette.DANGER)
    }

    private fun drawWord(scene: Scene) {
        val n = word.length
        if (n == 0) return
        val slotW = (viewWidth * 0.9f / n).coerceAtMost(WORD_MAX_SLOT_PT)
        val startX = (viewWidth - slotW * n) / 2f
        for (i in 0 until n) {
            val cx = startX + i * slotW + slotW / 2f
            val letterIdx = word[i] - 'a'
            if (guessed[letterIdx]) {
                scene.label(cx, wordY, LETTERS[letterIdx], Palette.FG, WORD_TEXT_SIZE_PT, Align.CENTER)
            } else {
                scene.line(cx - slotW * 0.3f, wordY + WORD_TEXT_SIZE_PT * 0.35f, cx + slotW * 0.3f, wordY + WORD_TEXT_SIZE_PT * 0.35f, Palette.FG_DIM, width = 3f)
            }
        }
    }

    private fun drawKeyboard(scene: Scene) {
        for (idx in 0 until 26) {
            val row = idx / KEY_COLS
            val col = idx % KEY_COLS
            val x = keyAreaX + col * keyW
            val y = keyAreaY + row * keyH
            val pad = keyW * 0.06f
            val bg = when {
                !guessed[idx] -> Palette.SURFACE
                word.indexOf('a' + idx) >= 0 -> Palette.OK
                else -> Palette.DANGER
            }
            scene.rect(x + pad, y + pad, keyW - 2 * pad, keyH - 2 * pad, bg, radius = pad)
            val textColor = if (guessed[idx]) Palette.BG else Palette.FG
            scene.label(x + keyW / 2f, y + keyH / 2f, LETTERS[idx], textColor, keyH * 0.45f, Align.CENTER)
        }
    }

    public companion object {
        public const val MAX_WRONG: Int = 7

        private val LETTERS: Array<String> = arrayOf(
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        )

        private const val KEY_COLS: Int = 9
        private const val KEY_ROWS: Int = 3
        private const val KEY_GAP_PT: Float = 4f
        private const val KEY_HEIGHT_PT: Float = 42f
        private const val WORD_AREA_H: Float = 40f
        private const val WORD_TEXT_SIZE_PT: Float = 26f
        private const val WORD_MAX_SLOT_PT: Float = 34f
        private const val GALLOWS_TOP_MARGIN_PT: Float = 8f

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "hangman",
            titleKey = StringKey.TITLE_HANGMAN,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.M,
            resumable = false,
            factory = { HangmanGame() },
            icon = { scene, size -> drawIcon(scene, size) },
        )

        private fun drawIcon(scene: Scene, size: Float) {
            scene.line(size * 0.2f, size * 0.9f, size * 0.6f, size * 0.9f, Palette.FG_DIM, width = size * 0.04f)
            scene.line(size * 0.3f, size * 0.9f, size * 0.3f, size * 0.1f, Palette.FG_DIM, width = size * 0.04f)
            scene.line(size * 0.3f, size * 0.1f, size * 0.65f, size * 0.1f, Palette.FG_DIM, width = size * 0.04f)
            scene.circle(size * 0.65f, size * 0.22f, size * 0.1f, Palette.ACCENT)
            scene.line(size * 0.65f, size * 0.32f, size * 0.65f, size * 0.55f, Palette.ACCENT, width = size * 0.04f)
        }
    }
}
