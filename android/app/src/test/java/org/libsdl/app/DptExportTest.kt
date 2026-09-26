package org.libsdl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #211 - the create/skip/remove rules for "Create DPT Files". Pure logic;
 * the SAF reads and writes and the Export page are checked by hand on the
 * real devices.
 */
class DptExportTest {

    @Test
    fun contentIsTheExactTwoLineTemplate() {
        assertEquals("# Daijishou Player Template\n[gamename] Alpha\n", dptContent("Alpha"))
    }

    @Test
    fun onlyDotDptCountsAsADptFile() {
        assertTrue(isDptFileName("Alpha.dpt"))
        assertTrue(isDptFileName("Alpha.DPT"))
        assertFalse(isDptFileName("Alpha.dpt.old"))
        assertFalse(isDptFileName("Alpha.txt"))
        assertFalse(isDptFileName("dpt"))
    }

    @Test
    fun ownDptIsTheTemplateForItsOwnName() {
        assertTrue(isOwnDpt("Alpha.dpt", dptContent("Alpha")))
        assertTrue(isOwnDpt("Alpha.dpt", "# Daijishou Player Template\r\n[gamename] Alpha\r\n"))
        assertTrue(isOwnDpt("Alpha.dpt", "# Daijishou Player Template\n[gamename] Alpha"))
        assertFalse(isOwnDpt("Alpha.dpt", dptContent("Beta")))
        assertFalse(isOwnDpt("Alpha.dpt", dptContent("Alpha") + "# my note\n"))
        assertFalse(isOwnDpt("Alpha.dpt", "[gamename] Alpha\n"))
    }

    @Test
    fun createsAFileForEveryGameWithoutOne() {
        val plan = planDptFolder(listOf("Alpha", "Beta"), listOf("Alpha", "Beta"), emptyMap())

        assertEquals(listOf("Alpha", "Beta"), plan.create)
        assertEquals(0, plan.skipped)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun existingFileIsSkippedNeverOverwrittenWhateverItsContentOrCase() {
        val existing = mapOf("Alpha.dpt" to "something the user wrote", "beta.DPT" to null)

        val plan = planDptFolder(listOf("Alpha", "Beta", "Gamma"), listOf("Alpha", "Beta", "Gamma"), existing)

        assertEquals(listOf("Gamma"), plan.create)
        assertEquals(2, plan.skipped)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun twoGamesDifferingOnlyInCaseGetOneFile() {
        val plan = planDptFolder(listOf("Alpha", "alpha"), listOf("Alpha", "alpha"), emptyMap())

        assertEquals(listOf("Alpha"), plan.create)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun staleFileInTheExactTemplateIsRemoved() {
        val existing = mapOf("Gone.dpt" to dptContent("Gone"))

        val plan = planDptFolder(listOf("Alpha"), listOf("Alpha"), existing)

        assertEquals(listOf("Gone.dpt"), plan.remove)
    }

    @Test
    fun staleFileWithAnyOtherContentIsKept() {
        val existing = mapOf(
            "Edited.dpt" to dptContent("Edited") + "# my note\n",
            "Other.dpt" to dptContent("SomethingElse"),
            "TooBig.dpt" to null,
        )

        val plan = planDptFolder(listOf("Alpha"), listOf("Alpha"), existing)

        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun fileForAGameStillListedIsKeptEvenIfItBelongsInTheOtherFolder() {
        // a Daphne game's file sitting in the LaserDisc folder
        val existing = mapOf("Daphne1.dpt" to dptContent("Daphne1"))

        val plan = planDptFolder(listOf("Singe1"), listOf("Singe1", "Daphne1"), existing)

        assertEquals(listOf("Singe1"), plan.create)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun resultMessageShowsCountsThenProblems() {
        assertEquals("Created 20, skipped 7, removed 2", dptResultMessage(DptExportResult(20, 7, 2, emptyList())))
        assertEquals(
            "Created 3, skipped 0, removed 0\ndaphne folder not found",
            dptResultMessage(DptExportResult(3, 0, 0, listOf("daphne folder not found"))),
        )
    }
}
