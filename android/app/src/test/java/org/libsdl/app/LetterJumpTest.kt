package org.libsdl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #209 - the jump-to-letter grouping and targets. Pure logic; the picker
 * itself and the focus behaviour around it are checked by hand on the real
 * devices.
 */
class LetterJumpTest {

    private fun game(name: String) = Game(name, GameCategory.SINGE_ZIPPED, "$name.txt", "$name.zip")

    // Same order the scanner produces (GameScanner.kt, #202): A to Z ignoring case.
    private fun sorted(vararg names: String): List<Game> =
        names.map(::game).sortedWith(compareBy<Game> { it.name.lowercase() }.thenBy { it.name })

    @Test
    fun entriesAreHashThenAToZ() {
        assertEquals(27, LETTER_ENTRIES.size)
        assertEquals("#", LETTER_ENTRIES.first())
        assertEquals("A", LETTER_ENTRIES[1])
        assertEquals("Z", LETTER_ENTRIES.last())
        assertEquals(('A'..'Z').map { it.toString() }, LETTER_ENTRIES.drop(1))
    }

    @Test
    fun nameBelongsToItsFirstLetterIgnoringCase() {
        assertEquals("A", letterEntryFor("alpha"))
        assertEquals("A", letterEntryFor("Alpha"))
        assertEquals("S", letterEntryFor("space_ace_1080"))
        assertEquals("Z", letterEntryFor("zebra"))
    }

    @Test
    fun digitsSymbolsAndEmptyNameGoUnderHash() {
        assertEquals("#", letterEntryFor("38ambush"))
        assertEquals("#", letterEntryFor("7up"))
        assertEquals("#", letterEntryFor("_hidden"))
        assertEquals("#", letterEntryFor("-x"))
        assertEquals("#", letterEntryFor(""))
    }

    @Test
    fun nonEnglishLetterGoesUnderHash() {
        assertEquals("#", letterEntryFor("éclair"))
    }

    @Test
    fun eachLetterJumpsToTheFirstGameStartingWithIt() {
        val games = sorted("38ambush", "alpha", "Beta", "Charlie", "delta")

        val targets = letterJumpTargets(games)

        assertEquals(0, targets["#"])
        assertEquals(1, targets["A"])
        assertEquals(2, targets["B"])
        assertEquals(3, targets["C"])
        assertEquals(4, targets["D"])
    }

    @Test
    fun capitalizedAndLowercaseNamesShareOneTargetTheEarliest() {
        val games = sorted("Sonic", "space", "Samurai", "sugar")

        val targets = letterJumpTargets(games)

        assertEquals(0, targets["S"])
        assertEquals(1, targets.size)
        assertEquals("Samurai", games[targets.getValue("S")].name)
    }

    @Test
    fun letterWithNoGamesHasNoTarget() {
        val targets = letterJumpTargets(sorted("alpha", "charlie"))

        assertTrue("A" in targets)
        assertTrue("C" in targets)
        assertNull(targets["B"])
        assertFalse("Z" in targets)
    }

    @Test
    fun emptyListHasNoTargets() {
        assertTrue(letterJumpTargets(emptyList()).isEmpty())
    }
}
