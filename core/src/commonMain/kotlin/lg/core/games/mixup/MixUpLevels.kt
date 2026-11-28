package lg.core.games.mixup

/** What a level asks for before its moves run out. */
internal enum class Goal { SCORE, COLLECT, ICE }

/**
 * One level: [moves] swaps, [colors] kinds of shapes, and a single goal. [collect] lists pairs (shape, how many);
 * [ice] is the starting ice, one string of 7 characters per row ('.', '1' or '2' layers).
 */
internal class Level(
    val moves: Int,
    val colors: Int,
    val goal: Goal,
    val scoreTarget: Int = 0,
    val collect: IntArray = IntArray(0),
    private val ice: Array<String> = emptyArray(),
) {
    fun iceAt(cell: Int): Int {
        if (ice.isEmpty()) return 0
        val c = ice[cell / MixUpBoard.COLS][cell % MixUpBoard.COLS]
        return if (c == '.') 0 else c - '0'
    }
}

private fun score(moves: Int, colors: Int, target: Int) = Level(moves, colors, Goal.SCORE, scoreTarget = target)

private fun collect(moves: Int, colors: Int, vararg pairs: Int) = Level(moves, colors, Goal.COLLECT, collect = pairs)

private fun ice(moves: Int, colors: Int, vararg rows: String) =
    Level(moves, colors, Goal.ICE, ice = arrayOf(*rows))

/** The thirty levels: score (1-8), collect (9-16), ice (17-24), then harder mixes (25-30). */
internal object MixUpLevels {
    val all: List<Level> = listOf(
        score(20, 5, 600), // 1
        score(20, 5, 900),
        score(20, 5, 1200),
        score(18, 5, 1300),
        score(22, 5, 1800), // 5
        score(20, 5, 1900),
        score(25, 5, 2600),
        score(22, 5, 2500),
        collect(25, 6, 0, 18), // 9
        collect(25, 6, 1, 22),
        collect(25, 6, 2, 16, 3, 16),
        collect(24, 6, 4, 18, 5, 18),
        collect(26, 6, 0, 24, 1, 24),
        collect(24, 6, 3, 28),
        collect(28, 6, 2, 28, 5, 28),
        collect(28, 6, 4, 30, 0, 30), // 16
        ice(25, 6, ".......", ".......", ".......", "..111..", "..111..", "..111..", ".......", ".......", "......."), // 17
        ice(25, 6, ".......", ".......", ".......", ".......", ".......", ".......", "1111111", "1111111", "......."),
        ice(28, 6, ".......", ".......", "1.....1", "1.....1", "1.....1", "1.....1", "1.....1", "1111111", "......."),
        ice(20, 6, ".......", ".......", "...1...", "..111..", ".11111.", "..111..", "...1...", ".......", "......."),
        ice(20, 6, ".......", ".......", ".......", "..222..", "..222..", ".......", ".......", ".......", "......."),
        ice(30, 6, ".......", ".......", ".......", ".......", ".......", "1111111", "2222222", ".......", "......."),
        ice(28, 6, ".......", "1.1.1.1", ".1.1.1.", "1.1.1.1", ".1.1.1.", "1.1.1.1", ".1.1.1.", ".......", "......."),
        ice(28, 6, ".......", ".......", "..111..", ".12221.", ".12221.", ".12221.", "..111..", ".......", "......."), // 24
        score(25, 6, 2200), // 25
        collect(26, 6, 0, 24, 2, 24, 4, 24),
        ice(30, 6, ".......", ".......", ".......", ".......", ".......", ".......", "1111111", "2222222", "1111111"),
        collect(28, 6, 1, 30, 3, 30, 5, 30),
        score(28, 6, 3000),
        ice(32, 6, ".......", ".......", "2.....2", "11...11", "1111111", "11...11", "2.....2", ".......", "......."), // 30
    )
}
