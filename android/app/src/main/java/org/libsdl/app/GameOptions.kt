package org.libsdl.app

import android.content.Context
import java.io.File

enum class CoverArtType { CD, LOGO, BOX, TEXT }

data class GameOptions(
    val coverArt: CoverArtType?, // null = no override, resolves to the app default (box)
    val bezelEnabled: Boolean,
    val arguments: List<String>,
    // #111 - only meaningful while the game's own scoreboard bezel is
    // actually active (either this Bezel toggle, or the game's own script
    // calling scoreBezelEnable() directly, like Esh's Aurunmilla does) - a
    // harmless no-op arg otherwise. Off by default, same fixed-ratio bezel
    // sizing as before unless explicitly enabled.
    val scorebezelAutofit: Boolean = false,
    // #117 - redraws the Singe overlay (score/lives/skip/arrows - anything a
    // game draws via spriteDraw) a second time on top of custom bezel art,
    // since it's otherwise buried under the bezel. Unrelated to
    // scorebezelAutofit/the Daphne-native scoreboard system. Off by default.
    val overlayBezel: Boolean = false,
    // #137 - per-game opt-in for a bezel sized to match the video's own
    // resolution (e.g. a 1920x1080 bezel for a 1920x1080 video): fits the
    // bezel to the video's aspect-corrected rect instead of the full
    // screen, only while Preserve Aspect Ratio (a separate, global setting)
    // is also on. Off by default - existing full-screen bezel behavior is
    // unchanged unless explicitly enabled per-game.
    val aspectBezelFix: Boolean = false,
    // #183 - Game Hack, scoped only to the attract preview (MpvPlayerView),
    // not real gameplay - a completely separate hypseus-native pipeline.
    // Adds a post-decode scale-down for performance; off by default, same
    // convention as every other Game Hack/Bezel toggle in this app.
    val reduceAttractVideoResolution: Boolean = false,
    // #185 - "Touch Lightgun" Game Hack. On passes hypseus's own
    // "-manymouse" flag (see cmdline.cpp), which routes mouse input through
    // the Android ManyMouse backend (hypseus-singe/src/manymouse/
    // android_touch.cpp) instead of the default SDL relative-mouse path -
    // confirmed on real hardware to be what makes tap-to-aim/tap-to-shoot
    // work at all. Off by default, same convention as every other Game
    // Hack toggle in this app.
    val touchLightgun: Boolean = false,
)

private const val GAME_OPTIONS_PREFS = "hypdroid_game_options"

private fun coverArtKey(gameName: String) = "coverart_$gameName"
private fun bezelKey(gameName: String) = "bezel_$gameName"
private fun argumentsKey(gameName: String) = "args_$gameName"
private fun scorebezelAutofitKey(gameName: String) = "scorebezel_autofit_$gameName"
private fun overlayBezelKey(gameName: String) = "overlaybezel_$gameName"
private fun aspectBezelFixKey(gameName: String) = "aspectbezelfix_$gameName"
private fun reduceAttractVideoResolutionKey(gameName: String) = "reduce_attract_video_res_$gameName"
private fun touchLightgunKey(gameName: String) = "touch_lightgun_$gameName"

fun loadGameOptions(context: Context, gameName: String): GameOptions {
    val prefs = context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
    val coverArt = prefs.getString(coverArtKey(gameName), null)?.let {
        try {
            CoverArtType.valueOf(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    val bezelEnabled = prefs.getBoolean(bezelKey(gameName), false)
    val arguments = (prefs.getString(argumentsKey(gameName), "") ?: "")
        .split("\n")
        .filter { it.isNotBlank() }
    val scorebezelAutofit = prefs.getBoolean(scorebezelAutofitKey(gameName), false)
    val overlayBezel = prefs.getBoolean(overlayBezelKey(gameName), false)
    val aspectBezelFix = prefs.getBoolean(aspectBezelFixKey(gameName), false)
    val reduceAttractVideoResolution = prefs.getBoolean(reduceAttractVideoResolutionKey(gameName), false)
    val touchLightgun = prefs.getBoolean(touchLightgunKey(gameName), false)
    return GameOptions(
        coverArt,
        bezelEnabled,
        arguments,
        scorebezelAutofit,
        overlayBezel,
        aspectBezelFix,
        reduceAttractVideoResolution,
        touchLightgun,
    )
}

fun saveCoverArt(context: Context, gameName: String, coverArt: CoverArtType) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(coverArtKey(gameName), coverArt.name)
        .apply()
}

fun saveBezelEnabled(context: Context, gameName: String, enabled: Boolean) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(bezelKey(gameName), enabled)
        .apply()
}

fun saveScorebezelAutofit(context: Context, gameName: String, enabled: Boolean) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(scorebezelAutofitKey(gameName), enabled)
        .apply()
}

fun saveOverlayBezel(context: Context, gameName: String, enabled: Boolean) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(overlayBezelKey(gameName), enabled)
        .apply()
}

fun saveAspectBezelFix(context: Context, gameName: String, enabled: Boolean) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(aspectBezelFixKey(gameName), enabled)
        .apply()
}

fun saveReduceAttractVideoResolution(context: Context, gameName: String, enabled: Boolean) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(reduceAttractVideoResolutionKey(gameName), enabled)
        .apply()
}

fun saveTouchLightgun(context: Context, gameName: String, enabled: Boolean) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(touchLightgunKey(gameName), enabled)
        .apply()
}

fun saveArguments(context: Context, gameName: String, arguments: List<String>) {
    context.getSharedPreferences(GAME_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(argumentsKey(gameName), arguments.joinToString("\n"))
        .apply()
}

// <media>/<box|cd|logo>/<gamename>.png - matches the subfolder convention
// from #30's planning. TEXT never has a file (it's the literal-text choice).
fun coverArtFile(mediaFolderPath: String?, gameName: String, type: CoverArtType): File? {
    if (mediaFolderPath == null || type == CoverArtType.TEXT) return null
    val subfolder = when (type) {
        CoverArtType.CD -> "cd"
        CoverArtType.LOGO -> "logo"
        CoverArtType.BOX -> "box"
        CoverArtType.TEXT -> return null
    }
    val file = File(mediaFolderPath, "$subfolder/$gameName.png")
    return if (file.exists()) file else null
}

// Resolves what the carousel should actually display for a game: an
// explicit per-game override if set (falling back to plain text if that
// specific type's file is missing, never silently substituting a
// different type than what was chosen), otherwise the app default (box).
fun resolveCoverArtFile(mediaFolderPath: String?, gameName: String, override: CoverArtType?): File? {
    val effectiveType = override ?: CoverArtType.BOX
    return coverArtFile(mediaFolderPath, gameName, effectiveType)
}

// <Game folder>/bezels/<gamename>.png - hypseus's own native convention,
// not a Hypdroid invention (#51): video.cpp:130's g_bezel_path defaults to
// the literal relative string "bezels", and homedir::set_homedir() already
// auto-creates this exact folder under -homedir on every single launch
// regardless of whether it's ever used. Since #60, -homedir is always the
// picked Game folder itself for every category, so this keys off
// gameFolderPath directly - no more per-category special-casing needed.
// Bezel no longer depends on the Media folder being set at all.
//
// -bezeldir must always be passed alongside -bezel - confirmed in
// video.cpp:130, hypseus's own relative default can never resolve
// correctly on this Android port (same SDL relative-path routing issue as
// #29's Bug 2).
fun bezelArtFile(gameFolderPath: String?, gameName: String): File? {
    if (gameFolderPath == null) return null
    val file = File(gameFolderPath, "bezels/$gameName.png")
    return if (file.exists()) file else null
}

fun bezelLaunchArgs(gameFolderPath: String?, gameName: String): List<String> {
    bezelArtFile(gameFolderPath, gameName) ?: return emptyList()
    val bezelDir = File(gameFolderPath, "bezels").absolutePath
    return listOf("-bezeldir", bezelDir, "-bezel", "$gameName.png")
}

// #47 - Global Cover Art override. App-wide, not per-game, so it lives in
// the same PREFS_NAME file as the folder paths rather than GAME_OPTIONS_PREFS
// above (which is keyed per-game and cleared/rescanned with the game list).
private const val PREF_GLOBAL_COVER_ART_ENABLED = "global_cover_art_enabled"
private const val PREF_GLOBAL_COVER_ART_TYPE = "global_cover_art_type"

fun loadGlobalCoverArtEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_GLOBAL_COVER_ART_ENABLED, false)
}

fun saveGlobalCoverArtEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_GLOBAL_COVER_ART_ENABLED, enabled)
        .apply()
}

fun loadGlobalCoverArtType(context: Context): CoverArtType {
    val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREF_GLOBAL_COVER_ART_TYPE, null)
    val parsed = stored?.let {
        try {
            CoverArtType.valueOf(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    return parsed ?: CoverArtType.BOX
}

fun saveGlobalCoverArtType(context: Context, type: CoverArtType) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_GLOBAL_COVER_ART_TYPE, type.name)
        .apply()
}

// #66 - Background Art. Two independent toggles, not an automatic fallback
// chain: "Background Art" is the master on/off switch (off = today's plain
// look, unconditionally); "Default Art" only matters while Background Art
// is on, and forces bg/default.png for every game, overriding any per-game
// bg/<gamename>.png - same override shape as Global Cover Art above.
private const val PREF_BACKGROUND_ART_ENABLED = "background_art_enabled"
private const val PREF_DEFAULT_ART_ENABLED = "default_art_enabled"

fun loadBackgroundArtEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_BACKGROUND_ART_ENABLED, false)
}

fun saveBackgroundArtEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_BACKGROUND_ART_ENABLED, enabled)
        .apply()
}

fun loadDefaultArtEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_DEFAULT_ART_ENABLED, false)
}

fun saveDefaultArtEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_DEFAULT_ART_ENABLED, enabled)
        .apply()
}

// #109 - off by default, matching hypseus's own existing screen-fill
// behavior unchanged. On, passes -preserve_aspect_ratio (a real source
// patch to hypseus-singe itself, see docs/ANDROID_PATCHES.md) so video
// keeps its real aspect ratio with letterbox/pillarbox bars instead of
// always filling the screen edge-to-edge.
private const val PREF_PRESERVE_ASPECT_RATIO_ENABLED = "preserve_aspect_ratio_enabled"

fun loadPreserveAspectRatioEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_PRESERVE_ASPECT_RATIO_ENABLED, false)
}

fun savePreserveAspectRatioEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_PRESERVE_ASPECT_RATIO_ENABLED, enabled)
        .apply()
}

// #163 - phase 1 of the Carousel Layout brainstorm (see private
// smoke/video-snaps-ideas.md). Off by default, matching today's carousel
// unchanged. On: GameCarousel reconfigures to one focused card per page,
// left-aligned instead of centered - no video frame yet, just proving out
// the layout/navigation first.
private const val PREF_ATTRACT_MODE_ENABLED = "attract_mode_enabled"

fun loadAttractModeEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_ATTRACT_MODE_ENABLED, false)
}

fun saveAttractModeEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_ATTRACT_MODE_ENABLED, enabled)
        .apply()
}

// <media>/bg/<gamename>.png or <media>/bg/default.png, gated entirely by
// the two toggles above - no automatic fallback when a per-game image is
// simply missing (that's a deliberate design choice, not an oversight):
// with Default Art off, a game with no bg art of its own just stays plain
// white, same as Background Art being off entirely.
fun backgroundArtFile(
    mediaFolderPath: String?,
    gameName: String,
    backgroundArtEnabled: Boolean,
    defaultArtEnabled: Boolean,
): File? {
    if (mediaFolderPath == null || !backgroundArtEnabled) return null
    val fileName = if (defaultArtEnabled) "default" else gameName
    val file = File(mediaFolderPath, "bg/$fileName.png")
    return if (file.exists()) file else null
}
