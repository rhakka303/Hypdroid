package org.libsdl.app

import java.io.File

enum class GameCategory { SINGE_ZIPPED, SINGE_SCRIPT, DAPHNE_NATIVE }

// altScript is set only for a game inside a multi-game pack: the startup .singe's name in the shared zip, passed to hypseus as -usealt.
data class Game(
    val name: String,
    val category: GameCategory,
    val framefilePath: String,
    val romOrScriptPath: String,
    val altScript: String? = null,
)

// A framefile has at least one "<frame number> <video file>.m2v" line, which tells it apart from a readme in the same folder.
private val FRAME_LINE = Regex("""^\s*\d+\s+\S+\.m2v""", RegexOption.IGNORE_CASE)
private const val FRAMEFILE_LINES_TO_CHECK = 200

// hypseus rejects a -usealt value with any character outside these.
private val USEALT_SAFE_NAME = Regex("^[A-Za-z0-9_.-]+$")

private fun looksLikeFramefile(file: File): Boolean =
    try {
        file.useLines { lines -> lines.take(FRAMEFILE_LINES_TO_CHECK).any { FRAME_LINE.containsMatchIn(it) } }
    } catch (e: java.io.IOException) {
        false
    }

// A folder with <folder>.zip and no <folder>.txt is a multi-game pack: one shared zip, one framefile per game.
private fun packGames(packDir: File): List<Game> {
    val zip = File(packDir, "${packDir.name}.zip")
    if (!zip.isFile) return emptyList()
    return packDir.listFiles { f -> f.isFile && f.extension.equals("txt", ignoreCase = true) }
        ?.filter { USEALT_SAFE_NAME.matches(it.nameWithoutExtension) && looksLikeFramefile(it) }
        ?.map { Game(it.nameWithoutExtension, GameCategory.SINGE_ZIPPED, it.path, zip.path, it.nameWithoutExtension) }
        ?: emptyList()
}

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
    val packedGames = mutableListOf<Game>()
    singeDir.listFiles { f -> f.isDirectory }?.forEach { gameDir ->
        val name = gameDir.name
        val framefile = File(gameDir, "$name.txt")
        if (!framefile.isFile) {
            packedGames += packGames(gameDir)
            return@forEach
        }

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

    // Ordinary and Daphne games win a name clash (ignoring case), then packs in path order.
    val taken = games.map { it.name.lowercase() }.toMutableSet()
    for (packed in packedGames.sortedBy { it.framefilePath }) {
        if (taken.add(packed.name.lowercase())) games += packed
    }

    // A plain sortedBy { it.name } puts every capitalized name before every lowercase one.
    return games.sortedWith(compareBy<Game> { it.name.lowercase() }.thenBy { it.name })
}
