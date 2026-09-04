package org.libsdl.app

import java.io.File
import java.util.zip.ZipFile

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

// #177 - Daphne games have no .singe/MovieFPS anywhere to read, so there's
// no real per-game frame rate to convert their framefile's small starting
// offset (a handful of frames, confirmed on real games - 151/3) into a
// precise time. A generic NTSC-era default is close enough - this is a
// decorative preview, not gameplay, same tolerance already accepted for
// nearest-keyframe seeking elsewhere in this feature.
private const val DAPHNE_ASSUMED_FPS = 29.97
private const val DAPHNE_ATTRACT_DURATION_SECONDS = 60.0

// #175 - one "offset filename" line from a framefile. offsetIntro01/end are
// frame numbers in a *global* space shared across every entry - a single-
// file framefile is just the special case of exactly one entry at offset 0.
private data class FramefileEntry(val offset: Int, val filename: String)

/**
 * Resolves a game's real attract clip, or null for the graceful "no video"
 * fallback case (this is the default/common case, not an error - see #168).
 */
fun findAttractClip(game: Game): AttractClipInfo? {
    return when (game.category) {
        GameCategory.SINGE_SCRIPT, GameCategory.SINGE_ZIPPED -> findSingeAttractClip(game)
        // #177 - completely different mechanism: no .singe/Lua at all
        // (hypseus runs real CPU emulation of the original arcade board's
        // program ROM for these), so there's no offsetIntro01 to read -
        // just start from the framefile's own declared offset and play a
        // fixed window, see findDaphneAttractClip.
        GameCategory.DAPHNE_NATIVE -> findDaphneAttractClip(game)
    }
}

/**
 * Singe games: offsetIntro01/offsetIntro01end (from the .singe script)
 * declare exactly which frame range is the attract clip, in a global
 * frame-number space that can span multiple physical files (#175) - resolved
 * to whichever file's own range it falls into. No filename heuristics
 * needed anywhere in this - the offset data answers "which file" directly,
 * by construction (see smoke/video-snaps-ideas.md for why an earlier
 * filename-guessing idea was wrong).
 */
private fun findSingeAttractClip(game: Game): AttractClipInfo? {
    val scriptText = when (game.category) {
        GameCategory.SINGE_SCRIPT -> readUnzippedScript(game)
        // #172 - the .singe script itself lives inside the zip at
        // singe/<name>/<name>.singe (confirmed against a real zip,
        // matches hypseus's own mandated BASEDIR = "singe" convention,
        // not per-author variance) - everything else (framefile,
        // video/audio files) is loose on disk exactly like the unzipped
        // case, unchanged below.
        GameCategory.SINGE_ZIPPED -> readZippedScript(game)
        GameCategory.DAPHNE_NATIVE -> null
    } ?: return null

    val startFrame = OFFSET_INTRO01_REGEX.find(scriptText)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val endFrame = OFFSET_INTRO01END_REGEX.find(scriptText)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val fps = MOVIE_FPS_REGEX.find(scriptText)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
    if (startFrame <= 0 && endFrame <= 0) return null
    if (endFrame <= startFrame || fps <= 0.0) return null

    val resolved = resolveFramefile(game) ?: return null

    // #175 - which physical file a global frame number falls into: the
    // entry with the largest offset that's still <= the target frame.
    // For a single-entry (offset 0) framefile this always resolves to
    // that one entry, same as the original single-file-only behavior.
    fun entryFor(frame: Int) = resolved.entries.lastOrNull { it.offset <= frame }

    val startEntry = entryFor(startFrame) ?: return null
    val endEntry = entryFor(endFrame) ?: return null
    // The attract range spanning across a file boundary isn't supported -
    // falls back gracefully rather than trying to stitch two files
    // together (see #175's explicitly-out-of-scope note).
    if (startEntry != endEntry) return null

    val videoFile = File(resolved.videoDir, startEntry.filename)
    if (!videoFile.isFile) return null
    val audioPath = matchingAudioPath(videoFile)

    // Local, in-file frame numbers - subtract the resolved entry's own
    // starting offset from the global frame numbers. Zero-cost for the
    // single-file case (entry offset is always 0 there).
    val localStartFrame = startFrame - startEntry.offset
    val localEndFrame = endFrame - startEntry.offset

    return AttractClipInfo(
        videoPath = videoFile.path,
        audioPath = audioPath,
        startSeconds = localStartFrame / fps,
        endSeconds = localEndFrame / fps,
    )
}

/**
 * #177 - Daphne games: no offset metadata exists anywhere to declare an
 * attract range, so this just starts from the framefile's own declared
 * offset (its first/only entry - real Daphne framefiles checked so far are
 * all single-entry) and plays a fixed duration from there, matching a real
 * measured attract length (Badlands' own attract sequence runs about a
 * minute). The existing Play-button/auto-stop-at-duration UI machinery
 * (MainActivity.kt) handles the rest unchanged - this only supplies where
 * to start and how long to play.
 */
private fun findDaphneAttractClip(game: Game): AttractClipInfo? {
    val resolved = resolveFramefile(game) ?: return null
    val entry = resolved.entries.minByOrNull { it.offset } ?: return null

    val videoFile = File(resolved.videoDir, entry.filename)
    if (!videoFile.isFile) return null
    val audioPath = matchingAudioPath(videoFile)

    val startSeconds = entry.offset / DAPHNE_ASSUMED_FPS
    return AttractClipInfo(
        videoPath = videoFile.path,
        audioPath = audioPath,
        startSeconds = startSeconds,
        endSeconds = startSeconds + DAPHNE_ATTRACT_DURATION_SECONDS,
    )
}

private class ResolvedFramefile(val videoDir: File, val entries: List<FramefileEntry>)

// Shared between the Singe and Daphne paths - reads and parses a game's
// framefile (directory-prefix line + "offset filename" entries), resolving
// the directory prefix against the framefile's own real location on disk.
private fun resolveFramefile(game: Game): ResolvedFramefile? {
    val framefile = File(game.framefilePath)
    if (!framefile.isFile) return null
    val lines = framefile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val prefix = lines[0]
    val entries = parseFramefileEntries(lines.drop(1)) ?: return null
    if (entries.isEmpty()) return null

    val gameDir = framefile.parentFile ?: return null
    val videoDir = if (prefix == ".") gameDir else File(gameDir, prefix)
    return ResolvedFramefile(videoDir, entries.sortedBy { it.offset })
}

// Matching .ogg, same basename - confirmed inconsistent even within one
// game's own file list (AlitaBattleAngel), so this is optional, not
// assumed to exist.
private fun matchingAudioPath(videoFile: File): String? {
    val audioFile = File(videoFile.parentFile, videoFile.nameWithoutExtension + ".ogg")
    return if (audioFile.isFile) audioFile.path else null
}

// Parses every "offset filename" line - fails the whole framefile (returns
// null) if any single line is malformed, rather than silently skipping a
// bad line, which could otherwise throw off every offset lookup after it.
private fun parseFramefileEntries(entryLines: List<String>): List<FramefileEntry>? {
    val entries = mutableListOf<FramefileEntry>()
    for (line in entryLines) {
        val parts = line.split(Regex("""\s+"""))
        if (parts.size < 2) return null
        val offset = parts[0].toIntOrNull() ?: return null
        entries.add(FramefileEntry(offset, parts[1]))
    }
    return entries
}

private fun readUnzippedScript(game: Game): String? {
    val scriptFile = File(game.romOrScriptPath)
    if (!scriptFile.isFile) return null
    return scriptFile.readText()
}

// #172 - opens the zip, reads just the one .singe entry's text, closes the
// zip. No extraction to disk, no persistent handle kept open. Any failure
// (zip unreadable, entry not found at the expected path) falls through to
// null gracefully rather than throwing - same "no video" outcome as every
// other unmet precondition above.
private fun readZippedScript(game: Game): String? {
    val zipFile = File(game.romOrScriptPath)
    if (!zipFile.isFile) return null
    return try {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("singe/${game.name}/${game.name}.singe") ?: return null
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) {
        null
    }
}
