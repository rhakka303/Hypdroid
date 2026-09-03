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

// #175 - one "offset filename" line from a framefile. offsetIntro01/end are
// frame numbers in a *global* space shared across every entry - a single-
// file framefile is just the special case of exactly one entry at offset 0.
private data class FramefileEntry(val offset: Int, val filename: String)

/**
 * Resolves a game's real attract clip, or null for the graceful "no video"
 * fallback case (this is the default/common case, not an error - see #168).
 *
 * Supports both single-file and multi-file framefiles (#175) - a global
 * frame number (from offsetIntro01/end) is resolved to whichever physical
 * file's own range it falls into, exactly matching what the game's own
 * .singe script already declares. No filename heuristics needed anywhere
 * in this - the offset data answers "which file" directly, by construction
 * (see smoke/video-snaps-ideas.md for why an earlier filename-guessing
 * idea was wrong).
 */
fun findAttractClip(game: Game): AttractClipInfo? {
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

    val framefile = File(game.framefilePath)
    if (!framefile.isFile) return null
    val lines = framefile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val prefix = lines[0]
    val entries = parseFramefileEntries(lines.drop(1)) ?: return null
    if (entries.isEmpty()) return null
    val sortedEntries = entries.sortedBy { it.offset }

    // #175 - which physical file a global frame number falls into: the
    // entry with the largest offset that's still <= the target frame.
    // For a single-entry (offset 0) framefile this always resolves to
    // that one entry, same as the original single-file-only behavior.
    fun entryFor(frame: Int) = sortedEntries.lastOrNull { it.offset <= frame }

    val startEntry = entryFor(startFrame) ?: return null
    val endEntry = entryFor(endFrame) ?: return null
    // The attract range spanning across a file boundary isn't supported -
    // falls back gracefully rather than trying to stitch two files
    // together (see #175's explicitly-out-of-scope note).
    if (startEntry != endEntry) return null

    val gameDir = framefile.parentFile ?: return null
    val videoDir = if (prefix == ".") gameDir else File(gameDir, prefix)
    val videoFile = File(videoDir, startEntry.filename)
    if (!videoFile.isFile) return null

    // Matching .ogg, same basename - confirmed inconsistent even within one
    // game's own file list (AlitaBattleAngel), so this is optional, not
    // assumed to exist.
    val audioFile = File(videoDir, videoFile.nameWithoutExtension + ".ogg")
    val audioPath = if (audioFile.isFile) audioFile.path else null

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
