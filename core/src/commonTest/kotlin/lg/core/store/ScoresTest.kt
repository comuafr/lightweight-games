package lg.core.store

import lg.core.testutil.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class ScoresTest {
    @Test
    public fun bestDefaultsToZero(): Unit {
        val scores = Scores(FakeStore())
        assertEquals(0, scores.best("snake"))
    }

    @Test
    public fun submitNewBestReturnsTrueAndPersists(): Unit {
        val scores = Scores(FakeStore())
        assertTrue(scores.submit("snake", 10))
        assertEquals(10, scores.best("snake"))
    }

    @Test
    public fun submitLowerScoreReturnsFalseAndKeepsBest(): Unit {
        val scores = Scores(FakeStore())
        scores.submit("snake", 10)
        assertFalse(scores.submit("snake", 5))
        assertEquals(10, scores.best("snake"))
    }

    @Test
    public fun submitEqualScoreReturnsFalse(): Unit {
        val scores = Scores(FakeStore())
        scores.submit("snake", 10)
        assertFalse(scores.submit("snake", 10))
    }

    @Test
    public fun scoresAreIsolatedPerGameId(): Unit {
        val scores = Scores(FakeStore())
        scores.submit("snake", 10)
        scores.submit("g2048", 500)
        assertEquals(10, scores.best("snake"))
        assertEquals(500, scores.best("g2048"))
    }

    // ---------------------------------------------------------------- high-score table

    private val defaults = intArrayOf(100, 60, 40, 20, 10)

    private fun names(lines: List<ScoreEntry>): List<String> = lines.map { it.name }

    @Test
    public fun tableIsTheDefaultsWhenNothingWasPlayed(): Unit {
        val lines = Scores(FakeStore()).table("snake", defaults)
        assertEquals(listOf(100, 60, 40, 20, 10), lines.map { it.score })
        assertEquals(listOf("COM", "REG", "ZOE", "BOB", "AXL"), names(lines))
        assertTrue(lines.all { it.isDefault })
    }

    @Test
    public fun theBestOfAFreshStoreIsNeverADefault(): Unit {
        val scores = Scores(FakeStore())
        scores.table("snake", defaults)
        assertEquals(0, scores.best("snake"))
    }

    @Test
    public fun onlyAScoreAboveTheLastLineQualifies(): Unit {
        val scores = Scores(FakeStore())
        assertFalse(scores.qualifies("snake", defaults, 0))
        assertFalse(scores.qualifies("snake", defaults, 10), "a tie with the last line does not qualify")
        assertTrue(scores.qualifies("snake", defaults, 11))
    }

    @Test
    public fun insertReturnsTheRankAndKeepsFiveSortedLines(): Unit {
        val scores = Scores(FakeStore())
        assertEquals(2, scores.insert("snake", defaults, "abc", 50))
        val lines = scores.table("snake", defaults)
        assertEquals(listOf(100, 60, 50, 40, 20), lines.map { it.score })
        assertEquals("ABC", lines[2].name)
        assertFalse(lines[2].isDefault)
        assertEquals(0, scores.insert("snake", defaults, "top", 500))
        assertEquals("TOP", scores.table("snake", defaults)[0].name)
        assertEquals(5, scores.table("snake", defaults).size)
    }

    @Test
    public fun aTieStaysBehindTheOlderLine(): Unit {
        val scores = Scores(FakeStore())
        assertEquals(3, scores.insert("snake", defaults, "AAA", 40), "a tie with a default goes below it")
        assertEquals(4, scores.insert("snake", defaults, "BBB", 40), "a tie with a player's line goes below it")
        assertEquals(listOf("COM", "REG", "ZOE", "AAA", "BBB"), names(scores.table("snake", defaults)))
    }

    @Test
    public fun aScoreThatDoesNotQualifyIsNotInserted(): Unit {
        val scores = Scores(FakeStore())
        assertEquals(-1, scores.insert("snake", defaults, "AAA", 5))
        assertTrue(scores.table("snake", defaults).all { it.isDefault })
    }

    @Test
    public fun namesAreCleanedToThreeUpperCaseLetters(): Unit {
        assertEquals("ABC", Scores.sanitizeName("abc"))
        assertEquals("ABC", Scores.sanitizeName("a1b-c2d"))
        assertEquals("AB-", Scores.sanitizeName("ab"))
        assertEquals("---", Scores.sanitizeName(""))
        assertEquals("---", Scores.sanitizeName("123"))
        assertEquals("CAF", Scores.sanitizeName("caf\u00e9"))
    }

    @Test
    public fun aCorruptedTableIsIgnoredWithoutFailing(): Unit {
        val store = FakeStore()
        store.putString("hs.snake", "garbage,AAA:x,AB:5,ZZZ:-3,;;;,QQQ:70")
        val lines = Scores(store).table("snake", defaults)
        assertEquals(listOf(100, 70, 60, 40, 20), lines.map { it.score })
        assertEquals("QQQ", lines[1].name)
    }

    @Test
    public fun aRecordFromBeforeTablesBecomesOneRealLineOnce(): Unit {
        val store = FakeStore()
        store.putInt("score.snake", 55)
        val scores = Scores(store)
        scores.migrateLegacy("snake")
        // A new best submitted afterwards must not be mistaken for an old record.
        scores.submit("snake", 80)
        scores.migrateLegacy("snake")
        val lines = scores.table("snake", defaults)
        assertEquals(listOf(100, 60, 55, 40, 20), lines.map { it.score })
        assertEquals("---", lines[2].name)
        assertEquals(80, scores.best("snake"))
    }

    @Test
    public fun resetAllBringsTheDefaultsBackAndKeepsProgress(): Unit {
        val store = FakeStore()
        val scores = Scores(store)
        scores.insert("snake", defaults, "ABC", 70)
        scores.submit("snake", 70)
        scores.saveLastInitials("ABC")
        scores.saveProgress("breakout", 6)
        store.putBoolean("set.sound", false)
        scores.resetAll(listOf("snake", "breakout"))
        assertEquals(0, scores.best("snake"))
        assertTrue(scores.table("snake", defaults).all { it.isDefault })
        assertEquals("", scores.lastInitials())
        assertEquals(6, scores.progress("breakout"))
        assertEquals(false, store.getBoolean("set.sound", true))
    }

    @Test
    public fun lastInitialsAreRemembered(): Unit {
        val scores = Scores(FakeStore())
        assertEquals("", scores.lastInitials())
        scores.saveLastInitials("zoe")
        assertEquals("ZOE", scores.lastInitials())
    }
}
