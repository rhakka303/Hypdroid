package org.libsdl.app

import java.io.File

enum class GameCategory { SINGE_ZIPPED, SINGE_SCRIPT, DAPHNE_NATIVE }

data class Game(
    val name: String,
    val category: GameCategory,
    val framefilePath: String,
    val romOrScriptPath: String,
)

/**
 * Scans a chosen home folder for both game categories hypseus supports.
 * Any folder/file that doesn't match a category's required layout is just
 * excluded from the result - never an error, per #28's acceptance criteria.
 */
fun scanGames(homeDir: File): List<Game> {
    val games = mutableListOf<Game>()

    // Fan-made (Singe) games live under singe/<name>/<name>.txt - a real
    // hypseus/Singe requirement, not a Hypdroid convention (#60): every
    // Singe game's own script hardcodes BASEDIR = "singe" and builds both
    // its own directory (MYDIR = BASEDIR .. "/" .. name) and its shared
    // Framework/FrameworkKimmy library path from that identical prefix, so
    // the game folders and any shared library folders must all be true
    // siblings inside one real "singe" folder - confirmed directly against
    // a real game script, not assumed.
    val singeDir = File(homeDir, "singe")
    singeDir.listFiles { f -> f.isDirectory }?.forEach { gameDir ->
        val name = gameDir.name
        val framefile = File(gameDir, "$name.txt")
        if (!framefile.isFile) return@forEach

        val zip = File(gameDir, "$name.zip")
        val script = File(gameDir, "$name.singe")
        when {
            zip.isFile -> games += Game(name, GameCategory.SINGE_ZIPPED, framefile.path, zip.path)
            script.isFile -> games += Game(name, GameCategory.SINGE_SCRIPT, framefile.path, script.path)
        }
    }

    // Daphne-native games: framefile under vldp/<name>/<name>.txt, ROM zip
    // under roms/<name>.zip - both required, both live at the home dir's top level.
    val vldpDir = File(homeDir, "vldp")
    val romsDir = File(homeDir, "roms")
    if (vldpDir.isDirectory) {
        vldpDir.listFiles { f -> f.isDirectory }?.forEach { gameDir ->
            val name = gameDir.name
            val framefile = File(gameDir, "$name.txt")
            val rom = File(romsDir, "$name.zip")
            if (framefile.isFile && rom.isFile) {
                games += Game(name, GameCategory.DAPHNE_NATIVE, framefile.path, rom.path)
            }
        }
    }

    // A plain sortedBy { it.name } puts every capitalized name before every lowercase one.
    return games.sortedWith(compareBy<Game> { it.name.lowercase() }.thenBy { it.name })
}
