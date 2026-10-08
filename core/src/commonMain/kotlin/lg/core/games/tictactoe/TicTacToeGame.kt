package lg.core.games.tictactoe

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.ai.Minimax
import lg.core.ai.MinimaxBoard
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Palette
import lg.core.scene.ArcadeIcons
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.Layout
import lg.core.util.Rect

/**
 * 3x3 tic-tac-toe. [cells] is flat-indexed `row * SIZE + col`, `0` empty,
 * `1` = X (player 1, always first), `2` = O (player 2).
 *
 * In 1-player mode, player 2 (O) is played by [Minimax] at full depth
 * (perfect play on a 3x3 board) via the [SearchBoard] adapter, which reuses
 * [cells] directly for the search (apply/undo mutate and restore it in
 * place, so search is allocation-free). The AI move is delayed by
 * [AI_DELAY_MS] (accumulated in [update]) so it is visible to the player;
 * [wantsFrames] is true only while that delay is running.
 */
public class TicTacToeGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && awaitingAiMove

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private val cells = IntArray(CELL_COUNT)
    private var playerCount: Int = 1
    private var currentPlayer: Int = 1

    private var awaitingAiMove: Boolean = false
    private var aiTimerMs: Int = 0
    private val searchBoard = SearchBoard()

    private var winnerLineStart: Int = -1
    private var winnerLineEnd: Int = -1

    // Events from the most recent action, read (not mutated) by render().
    private var moveThisFrame: Boolean = false
    private var wonThisFrame: Boolean = false
    private var drawThisFrame: Boolean = false

    override fun reset(seed: Long, players: Int) {
        playerCount = players.coerceIn(1, 2)
        cells.fill(0)
        currentPlayer = 1
        awaitingAiMove = false
        aiTimerMs = 0
        winnerLineStart = -1
        winnerLineEnd = -1
        moveThisFrame = false
        wonThisFrame = false
        drawThisFrame = false
        score = 0
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        Layout.fitSquare(viewWidth, viewHeight, HUD_TOP_PT, boardRect)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // TicTacToe is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return
        if (awaitingAiMove) return
        val idx = cellAt(gesture.x, gesture.y) ?: return
        if (cells[idx] != 0) return
        playMove(idx, currentPlayer)
    }

    // Scratch line endpoints filled in by hasWinningLine(); read immediately by its caller.
    private var lineStartScratch: Int = -1
    private var lineEndScratch: Int = -1

    private fun playMove(idx: Int, player: Int) {
        cells[idx] = player
        score++
        moveThisFrame = true

        if (hasWinningLine(player)) {
            winnerLineStart = lineStartScratch
            winnerLineEnd = lineEndScratch
            status = GameStatus.Over(player)
            wonThisFrame = true
            return
        }
        if (isFull()) {
            status = GameStatus.Over(0)
            drawThisFrame = true
            return
        }

        currentPlayer = if (player == 1) 2 else 1
        if (playerCount == 1 && currentPlayer == 2) {
            awaitingAiMove = true
            aiTimerMs = 0
        }
    }

    override fun update(dtMs: Int): Boolean {
        moveThisFrame = false
        wonThisFrame = false
        drawThisFrame = false
        if (status != GameStatus.Running || !awaitingAiMove) return false

        aiTimerMs += dtMs
        if (aiTimerMs < AI_DELAY_MS) return false
        awaitingAiMove = false
        performAiMove()
        return true
    }

    private fun performAiMove() {
        val move = Minimax.bestMove(searchBoard, player = 2, opponent = 1, maxDepth = CELL_COUNT)
        if (move >= 0) playMove(move, 2)
    }

    private fun cellAt(x: Float, y: Float): Int? {
        if (boardRect.w <= 0f) return null
        if (x < boardRect.x || x > boardRect.x + boardRect.w) return null
        if (y < boardRect.y || y > boardRect.y + boardRect.h) return null
        val cell = boardRect.w / SIZE
        val col = ((x - boardRect.x) / cell).toInt().coerceIn(0, SIZE - 1)
        val row = ((y - boardRect.y) / cell).toInt().coerceIn(0, SIZE - 1)
        return row * SIZE + col
    }

    private fun isFull(): Boolean {
        for (c in cells) if (c == 0) return false
        return true
    }

    /** True if [player] has a winning line; fills [lineStartScratch]/[lineEndScratch] when so. */
    private fun hasWinningLine(player: Int): Boolean {
        for (line in WIN_LINES) {
            if (cells[line[0]] == player && cells[line[1]] == player && cells[line[2]] == player) {
                lineStartScratch = line[0]
                lineEndScratch = line[2]
                return true
            }
        }
        return false
    }

    // --- Test-only accessors (internal: exercised by TicTacToeRulesTest) ----

    internal fun debugCell(idx: Int): Int = cells[idx]
    internal fun debugCurrentPlayer(): Int = currentPlayer
    internal fun debugSetCell(idx: Int, value: Int) {
        cells[idx] = value
    }
    internal fun debugSetCurrentPlayer(player: Int) {
        currentPlayer = player
    }
    internal fun debugPlayMove(idx: Int, player: Int): Unit = playMove(idx, player)
    internal fun debugForceAiMove(): Unit = performAiMove()
    internal fun debugAiSuggestedMove(): Int = Minimax.bestMove(searchBoard, player = 2, opponent = 1, maxDepth = CELL_COUNT)

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.02f)

        val cell = boardRect.w / SIZE
        for (i in 1 until SIZE) {
            val gx = boardRect.x + i * cell
            scene.line(gx, boardRect.y, gx, boardRect.y + boardRect.h, Palette.GRID, width = 2f)
            val gy = boardRect.y + i * cell
            scene.line(boardRect.x, gy, boardRect.x + boardRect.w, gy, Palette.GRID, width = 2f)
        }

        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val idx = row * SIZE + col
                val cx = boardRect.x + col * cell + cell / 2f
                val cy = boardRect.y + row * cell + cell / 2f
                when (cells[idx]) {
                    1 -> drawX(scene, cx, cy, cell)
                    2 -> drawO(scene, cx, cy, cell)
                }
            }
        }

        if (winnerLineStart >= 0) {
            val sx = boardRect.x + (winnerLineStart % SIZE) * cell + cell / 2f
            val sy = boardRect.y + (winnerLineStart / SIZE) * cell + cell / 2f
            val ex = boardRect.x + (winnerLineEnd % SIZE) * cell + cell / 2f
            val ey = boardRect.y + (winnerLineEnd / SIZE) * cell + cell / 2f
            scene.line(sx, sy, ex, ey, Palette.ACCENT, width = cell * 0.1f)
        }

        if (moveThisFrame) scene.sound(SoundId.TICK)
        if (wonThisFrame) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
        if (drawThisFrame) scene.sound(SoundId.TICK)
    }

    private fun drawX(scene: Scene, cx: Float, cy: Float, cell: Float) {
        val r = cell * 0.32f
        val w = cell * 0.1f
        scene.line(cx - r, cy - r, cx + r, cy + r, Palette.P1, width = w)
        scene.line(cx - r, cy + r, cx + r, cy - r, Palette.P1, width = w)
    }

    private fun drawO(scene: Scene, cx: Float, cy: Float, cell: Float) {
        val r = cell * 0.32f
        scene.circle(cx, cy, r, Palette.P2)
        scene.circle(cx, cy, r * 0.6f, Palette.BG)
    }

    /**
     * Adapts [TicTacToeGame.cells] to [MinimaxBoard]: reuses the actual game
     * array for search, so `apply`/`undo` are allocation-free and always
     * leave [cells] exactly as found once a top-level search finishes.
     */
    private inner class SearchBoard : MinimaxBoard {
        override fun moveCount(): Int {
            var n = 0
            for (c in cells) if (c == 0) n++
            return n
        }

        override fun move(i: Int): Int {
            var remaining = i
            for (idx in 0 until CELL_COUNT) {
                if (cells[idx] == 0) {
                    if (remaining == 0) return idx
                    remaining--
                }
            }
            return -1
        }

        override fun apply(id: Int, player: Int) {
            cells[id] = player
        }

        override fun undo(id: Int) {
            cells[id] = 0
        }

        override fun terminalScore(player: Int): Int? {
            val opponent = if (player == 1) 2 else 1
            if (hasWinningLine(player)) return Minimax.WIN_SCORE
            if (hasWinningLine(opponent)) return -Minimax.WIN_SCORE
            if (isFull()) return 0
            return null
        }

        override fun heuristic(player: Int): Int = 0
    }

    public companion object {
        public const val SIZE: Int = 3
        public const val CELL_COUNT: Int = SIZE * SIZE

        private const val AI_DELAY_MS: Int = 350

        private val WIN_LINES: Array<IntArray> = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "tictactoe",
            titleKey = StringKey.TITLE_TICTACTOE,
            tier = Tier.UNLOCKED,
            players = 1..2,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.S,
            resumable = false,
            factory = { TicTacToeGame() },
            icon = { scene, size -> ArcadeIcons.threeInLine(scene, size) },
        )
    }
}
