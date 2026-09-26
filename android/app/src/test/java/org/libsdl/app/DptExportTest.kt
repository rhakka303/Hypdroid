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

    // #217 - games are grouped by the pack folder their framefile is in.
    private fun singe(name: String) = Game(name, GameCategory.SINGE_ZIPPED, "/home/singe/$name/$name.txt", "/home/singe/$name/$name.zip")
    private fun packed(pack: String, name: String) =
        Game(name, GameCategory.SINGE_ZIPPED, "/home/singe/$pack/$name.txt", "/home/singe/$pack/$pack.zip", name)
    private fun daphne(name: String) = Game(name, GameCategory.DAPHNE_NATIVE, "/home/vldp/$name/$name.txt", "/home/roms/$name.zip")

    private val mixed = listOf(
        singe("Hero"),
        daphne("disc1"),
        packed("actionmax", "am1"),
        packed("ActionMax", "am2"),
        packed("videodriver", "vd1"),
        packed("captainpower", "cp1"),
        packed("otherpack", "op1"),
    )

    @Test
    fun eachPackGoesToItsOwnSystem() {
        assertEquals(listOf("am1", "am2"), systemDptGames(mixed, DptSystem.ACTION_MAX).map { it.name })
        assertEquals(listOf("vd1"), systemDptGames(mixed, DptSystem.VIDEO_DRIVER).map { it.name })
        assertEquals(listOf("cp1"), systemDptGames(mixed, DptSystem.CAPTAIN_POWER).map { it.name })
    }

    @Test
    fun laserdiscSkipsTheThreePacksButKeepsOtherSingeGames() {
        assertEquals(listOf("Hero", "op1"), laserdiscDptGames(mixed).map { it.name })
    }

    @Test
    fun daphneGamesStayInDaphneOnly() {
        assertEquals(listOf("disc1"), daphneDptGames(mixed).map { it.name })
        assertEquals(null, dptSystemFor(daphne("actionmax")))
    }

    @Test
    fun cardsNeverShareAGame() {
        val groups = listOf(daphneDptGames(mixed), laserdiscDptGames(mixed)) +
            DptSystem.values().map { systemDptGames(mixed, it) }
        assertEquals(mixed.size, groups.sumOf { it.size })
        assertEquals(mixed.map { it.name }.toSet(), groups.flatten().map { it.name }.toSet())
    }

    @Test
    fun systemLabelsMatchTheFolderNotFoundMessages() {
        assertEquals(listOf("actionmax", "videodriver", "cpower"), DptSystem.values().map { it.label })
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
