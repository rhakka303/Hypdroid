package org.libsdl.app

/**
 * Builds the CLI-equivalent argv hypseus expects, mirroring the desktop
 * invocations from the README/doc/CmdLine.md - minus argv[0]. SDL's own
 * nativeRunMain() (SDL_android.c) always hardcodes argv[0] to "app_process"
 * itself and appends this array starting at argv[1], so this list must
 * start directly with the game-type token, not a program-name placeholder -
 * getting that wrong shifts every arg by one and makes hypseus's parser
 * read the game-type slot as garbage, which fails parse_game_type() and
 * exits cleanly before anything else runs (caught via a real on-device
 * boot test: this exact bug produced a silent "exited cleanly (1)").
 *
 * For DAPHNE_NATIVE, the first element must be one of hypseus's own hardcoded game
 * shortnames (see src/io/cmdline.cpp's dispatch - "lair", "ace", "cliff",
 * etc.), not an arbitrary folder name - this only works because Daphne
 * romsets are conventionally already named that way, which is also why
 * GameScanner uses the folder/rom name directly as Game.name for this
 * category. For SINGE_ZIPPED/SINGE_SCRIPT, the actual game identity comes
 * entirely from the dynamic -zlua/-script Lua content, so argv[1] is
 * always the fixed literal "singe".
 *
 * "vldp" (argv[2]) selects hypseus's software MPEG laserdisc-player
 * backend - always used here since there's no real LDP hardware.
 *
 * -homedir/-datadir is always the picked Game folder itself, for both
 * categories (#60) - the picked folder is required to directly contain
 * singe/, roms/, and vldp/ as true immediate children (see GameScanner.kt),
 * matching both hypseus's own Daphne-native ROM lookup convention
 * (game.cpp's load_roms() -> homedir::get_romfile() builds
 * "<homedir>/roms/<name>.zip") and Singe's own hardcoded BASEDIR = "singe"
 * convention used by every fan-made game's script. An earlier version of
 * this passed the picked folder's *parent* as -homedir for Singe games,
 * working around the picked folder itself having been named "singe" - that
 * was a real workaround for a real folder-naming coincidence, not a genuine
 * requirement, and broke down once the picked folder needed to hold roms/
 * and vldp/ too (see #60's full history for why the naive "just re-pick the
 * parent" fix was wrong before this).
 */
fun buildLaunchArgs(game: Game, homeDir: String): Array<String> {
    val args = mutableListOf<String>()

    when (game.category) {
        GameCategory.DAPHNE_NATIVE -> {
            args += game.name
            args += "vldp"
            args += listOf("-framefile", game.framefilePath)
        }
        GameCategory.SINGE_ZIPPED -> {
            args += "singe"
            args += "vldp"
            args += listOf("-framefile", game.framefilePath, "-zlua", game.romOrScriptPath)
            // A pack game's script is <altScript>.singe in the shared zip, not <zipname>.singe.
            game.altScript?.let { args += listOf("-usealt", it) }
        }
        GameCategory.SINGE_SCRIPT -> {
            args += "singe"
            args += "vldp"
            args += listOf("-framefile", game.framefilePath, "-script", game.romOrScriptPath)
        }
    }

    args += listOf("-homedir", "$homeDir/", "-datadir", "$homeDir/")
    // Baked-in defaults for every launch, per the owner: real fullscreen
    // gameplay, and SDL_Gamepad enabled (this is gamepad-first hardware).
    // No -haptic - rumble tuning is a later, separate decision.
    args += listOf("-fullscreen", "-gamepad")

    return args.toTypedArray()
}
