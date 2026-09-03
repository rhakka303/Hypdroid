package org.libsdl.app

import java.io.File

/**
 * #168 - resolved attract-clip info for one game: which video file, which
 * (optional) matching audio file, and where the attract range starts/ends
 * in seconds. Everything needed to hand straight to MpvPlayerView.
 */
data class AttractClipInfo(
    val videoPath: String,
    val audioPath: String?,
    val startSeconds: Double,
    val endSeconds: Double,
)

// Matches "offsetIntro01 = 171" / "MovieFPS = 23.976" - plain numeric
// variable assignments near the top of a .singe script. No Lua runtime
// needed to read these (see smoke/video-snaps-ideas.md).
private val OFFSET_INTRO01_REGEX = Regex("""offsetIntro01\s*=\s*(\d+)""")
private val OFFSET_INTRO01END_REGEX = Regex("""offsetIntro01end\s*=\s*(\d+)""")
private val MOVIE_FPS_REGEX = Regex("""MovieFPS\s*=\s*([\d.]+)""")

/**
 * Resolves a game's real attract clip, or null for the graceful "no video"
 * fallback case (this is the default/common case, not an error - see #168).
 *
 * Deliberately narrow scope, matching #168's settled decision to keep phase
 * 2 simple: only SINGE_SCRIPT (unzipped) games with exactly one entry in
 * their framefile. Zipped games, Daphne-native, multi-file framefiles, and
 * games with no offsetIntro01/MovieFPS all fall through to null the same
 * way - no special-case handling needed for each, "no video" is always a
 * safe, already-designed-for outcome.
 */
fun findAttractClip(game: Game): AttractClipInfo? {
    if (game.category != GameCategory.SINGE_SCRIPT) return null

    val scriptFile = File(game.romOrScriptPath)
    if (!scriptFile.isFile) return null
    val scriptText = scriptFile.readText()

    val startFrame = OFFSET_INTRO01_REGEX.find(scriptText)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val endFrame = OFFSET_INTRO01END_REGEX.find(scriptText)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val fps = MOVIE_FPS_REGEX.find(scriptText)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
    if (startFrame <= 0 && endFrame <= 0) return null
    if (endFrame <= startFrame || fps <= 0.0) return null

    val framefile = File(game.framefilePath)
    if (!framefile.isFile) return null
    val lines = framefile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val prefix = lines[0]
    // Single-file games only (#168's settled scope) - a multi-file
    // framefile (like AlitaBattleAngel.txt) means the offsets live in a
    // shared global frame space spanning several files, which needs real
    // per-file range lookup - not implemented yet, falls back to null
    // exactly like "no offset data found" (see smoke/video-snaps-ideas.md).
    val entryLines = lines.drop(1)
    if (entryLines.size != 1) return null
    val parts = entryLines[0].split(Regex("""\s+"""))
    if (parts.size < 2) return null
    val videoFilename = parts[1]

    val gameDir = scriptFile.parentFile ?: return null
    val videoDir = if (prefix == ".") gameDir else File(gameDir, prefix)
    val videoFile = File(videoDir, videoFilename)
    if (!videoFile.isFile) return null

    // Matching .ogg, same basename - confirmed inconsistent even within one
    // game's own file list (AlitaBattleAngel), so this is optional, not
    // assumed to exist.
    val audioFile = File(videoDir, videoFile.nameWithoutExtension + ".ogg")
    val audioPath = if (audioFile.isFile) audioFile.path else null

    return AttractClipInfo(
        videoPath = videoFile.path,
        audioPath = audioPath,
        startSeconds = startFrame / fps,
        endSeconds = endFrame / fps,
    )
}
