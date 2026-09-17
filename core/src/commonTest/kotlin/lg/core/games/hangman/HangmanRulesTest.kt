package lg.core.games.hangman

import lg.core.CoreConfig
import lg.core.GameStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class HangmanRulesTest {
    @BeforeTest
    public fun resetLanguage(): Unit {
        CoreConfig.languageTag = "en"
    }

    @AfterTest
    public fun restoreLanguage(): Unit {
        CoreConfig.languageTag = "en"
    }

    private fun newGame(seed: Long = 1L): HangmanGame {
        val game = HangmanGame()
        game.resize(360f, 640f)
        game.reset(seed)
        return game
    }

    @Test
    public fun correctLetterRevealsAllOccurrences(): Unit {
        val game = newGame()
        val word = game.debugWord()
        val letter = word[0]
        val idx = letter - 'a'
        game.debugGuessLetter(idx)
        assertTrue(game.debugGuessed(idx))
        // Every occurrence of `letter` in the word is now considered revealed.
        for (c in word) if (c == letter) assertTrue(game.debugGuessed(c - 'a'))
    }

    @Test
    public fun wrongLetterIncrementsMisses(): Unit {
        val game = newGame()
        val word = game.debugWord()
        var wrongLetterIdx = -1
        for (i in 0 until 26) {
            val ch = 'a' + i
            if (word.indexOf(ch) < 0) {
                wrongLetterIdx = i
                break
            }
        }
        assertTrue(wrongLetterIdx >= 0, "expected at least one letter not in the word")
        game.debugGuessLetter(wrongLetterIdx)
        assertEquals(1, game.debugWrongCount())
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun sevenMissesLosesTheGame(): Unit {
        val game = newGame()
        val word = game.debugWord()
        var misses = 0
        var i = 0
        while (misses < HangmanGame.MAX_WRONG && i < 26) {
            val ch = 'a' + i
            if (word.indexOf(ch) < 0) {
                game.debugGuessLetter(i)
                misses++
            }
            i++
        }
        assertEquals(HangmanGame.MAX_WRONG, misses)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
        assertEquals(0, game.score)
    }

    @Test
    public fun guessingTheWholeWordWins(): Unit {
        val game = newGame()
        val word = game.debugWord()
        val guessedLetters = BooleanArray(26)
        for (c in word) {
            val idx = c - 'a'
            if (!guessedLetters[idx]) {
                game.debugGuessLetter(idx)
                guessedLetters[idx] = true
            }
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, game.score)
    }

    @Test
    public fun repeatedGuessOfTheSameLetterIsIgnored(): Unit {
        val game = newGame()
        val word = game.debugWord()
        val letter = word[0]
        val idx = letter - 'a'
        game.debugGuessLetter(idx)
        val wrongBefore = game.debugWrongCount()
        game.debugGuessLetter(idx)
        assertEquals(wrongBefore, game.debugWrongCount())
    }

    @Test
    public fun frenchLanguageTagPicksTheFrenchWordList(): Unit {
        CoreConfig.languageTag = "fr-FR"
        val game = newGame(seed = 9L)
        val word = game.debugWord()
        assertTrue(WordsFr.WORDS.contains(word))
    }

    @Test
    public fun defaultLanguagePicksTheEnglishWordList(): Unit {
        CoreConfig.languageTag = "en"
        val game = newGame(seed = 9L)
        val word = game.debugWord()
        assertTrue(WordsEn.WORDS.contains(word))
    }
}
