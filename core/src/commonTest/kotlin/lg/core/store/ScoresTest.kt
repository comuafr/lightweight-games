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
}
