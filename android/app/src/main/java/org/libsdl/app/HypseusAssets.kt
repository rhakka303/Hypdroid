package org.libsdl.app

import android.content.Context
import java.io.File

// Bundled directly under assets/hypseus/ (see android/app/src/main/assets/hypseus/,
// copied from the vendored hypseus-singe/{pics,fonts,sound,midi,doc} at build time).
// These are hypseus's own common runtime assets, not user game content, required
// regardless of which game folder gets picked.
//
// pics/fonts/sound/midi go to INTERNAL storage, not the SD card home folder - this
// is not a style choice, it's required by how hypseus itself resolves these paths.
// video.cpp's load_one_bmp() passes a bare relative string ("pics/led0.bmp") straight
// into SDL_LoadBMP(), and SDL3's Android backend (SDL_iostream.c) special-cases any
// relative path: it first tries <getFilesDir()>/pics/led0.bmp (real internal-storage
// fopen()), and only falls back to the APK's own bundled assets after that - it never
// looks at the process's cwd (hypseus's own -datadir chdir() is irrelevant here) and
// never looks at -homedir. Confirmed by reading SDL's actual source, and by watching
// identical "Could not load bitmap" failures on a real device even after the exact
// same files were verified present on the SD card - the SD-card copy was simply never
// consulted for this lookup.
//
// hypinput.ini/hypinput_gamepad.ini are deliberately NOT bundled anywhere, on
// either internal storage or the SD card home folder. input.cpp's find_file()
// only ever resolves them via -homedir (an SD-card path here) or a hardcoded
// "." fallback that isn't Android-storage-aware either way - there's no clean
// path to internal storage for these without patching hypseus's own C++ source.
// Per the owner: the SD card home folder should hold pure game content only,
// nothing hypseus-install-related. input.cpp already has a fully graceful
// fallback if these are missing (hardcoded default key/gamepad bindings, just
// a warning logged, confirmed via source read of input.cpp:425) - so skipping
// them entirely is correct, not a shortcut. Real gamepad-mapping tuning for
// the actual target hardware is Phase F's job, not this one.
private val INTERNAL_ASSET_DIRS = listOf("pics", "fonts", "sound", "midi")

/**
 * Copies hypseus's bundled common assets into internal storage, skipping
 * anything already present - cheap to call on every folder pick, not just
 * the first one.
 *
 * #205: copies subfolders too (pics/lights/ holds the bezel light images).
 * AssetManager.list() returns subfolder names alongside file names, so the old
 * flat loop tried to open "lights" as a file. Each file is checked on its own
 * instead of comparing a folder's file count, so an in-place upgrade that adds
 * new files (or a whole new subfolder) gets them without a reinstall.
 */
fun ensureHypseusAssets(context: Context) {
    for (dirName in INTERNAL_ASSET_DIRS) {
        copyAssetDir(context, "hypseus/$dirName", File(context.filesDir, dirName))
    }
}

private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
    val assetManager = context.assets
    val entries = assetManager.list(assetPath) ?: return
    destDir.mkdirs()
    for (name in entries) {
        val childPath = "$assetPath/$name"
        // a folder lists its own entries; a file lists none
        if (!assetManager.list(childPath).isNullOrEmpty()) {
            copyAssetDir(context, childPath, File(destDir, name))
            continue
        }
        val destFile = File(destDir, name)
        if (destFile.exists()) continue
        assetManager.open(childPath).use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
