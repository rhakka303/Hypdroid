package org.libsdl.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * #211 - "Create DPT Files": one `<game name>.dpt` per game, for frontends
 * (Daijishō, ES-DE) that list games from one file per game and launch each
 * by name through #116's `gamename` extra.
 *
 * Daphne games' files go to the chosen Daphne folder, every other game's to
 * the chosen LaserDisc folder. #217 - games from the actionmax, videodriver
 * and captainpower packs go to their own chosen folders instead (the Export
 * page's second card), never to LaserDisc. A folder not set or not found
 * skips its games. Nothing is ever written to singe/.
 *
 * Same rules as the Windows launcher's TXT export:
 * - An existing `<game>.dpt` is skipped, never overwritten.
 * - A `.dpt` whose game is no longer listed is removed only if it is
 *   provably ours: exactly the template for its own name. Any other file
 *   is never touched.
 * - Only the root of each folder is looked at.
 */
private const val DPT_EXTENSION = "dpt"

// Every file this writes is the two-line template, so anything bigger
// can't be one of ours - checked before reading.
private const val MAX_OWN_DPT_BYTES = 1024L

fun dptContent(gameName: String): String =
    "# Daijishou Player Template\n[gamename] $gameName\n"

fun isDptFileName(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").equals(DPT_EXTENSION, ignoreCase = true)

/** Line endings and a missing last newline don't matter; anything else does. */
fun isOwnDpt(fileName: String, content: String): Boolean {
    val ownName = fileName.substringBeforeLast('.')
    return content.replace("\r\n", "\n").trimEnd('\n') == dptContent(ownName).trimEnd('\n')
}

data class DptFolderPlan(val create: List<String>, val skipped: Int, val remove: List<String>)

/**
 * What to do in one folder. [folderGames] are the games whose file belongs
 * here, [allGameNames] every game Hypdroid lists (a game still listed is
 * never stale, whichever folder its file sits in). [existing] is every
 * `.dpt` in the folder's root by file name, with its content, or null when
 * it was too big or unreadable to prove it's ours.
 */
fun planDptFolder(
    folderGames: List<String>,
    allGameNames: Collection<String>,
    existing: Map<String, String?>,
): DptFolderPlan {
    val present = existing.keys.map { it.lowercase() }.toMutableSet()
    val create = mutableListOf<String>()
    var skipped = 0
    for (name in folderGames) {
        // the SD card ignores case, so Game.dpt and game.dpt are one file
        if (present.add("$name.$DPT_EXTENSION".lowercase())) create += name else skipped++
    }

    val listed = allGameNames.map { it.lowercase() }.toSet()
    val remove = existing.filter { (fileName, content) ->
        fileName.substringBeforeLast('.').lowercase() !in listed &&
            content != null && isOwnDpt(fileName, content)
    }.keys.sorted()

    return DptFolderPlan(create, skipped, remove)
}

data class DptExportResult(val created: Int, val skipped: Int, val removed: Int, val problems: List<String>)

fun dptResultMessage(result: DptExportResult): String =
    (listOf("Created ${result.created}, skipped ${result.skipped}, removed ${result.removed}") + result.problems)
        .joinToString("\n")

/**
 * #217 - pack systems with their own folder on the Export page's second
 * card. [label] names the folder in "... folder not found"; [packFolder] is
 * the pack's folder name in singe/.
 */
enum class DptSystem(val label: String, val packFolder: String) {
    ACTION_MAX("actionmax", "actionmax"),
    VIDEO_DRIVER("videodriver", "videodriver"),
    CAPTAIN_POWER("cpower", "captainpower"),
}

/** Which pack system a game belongs to, from the folder its framefile is in. */
fun dptSystemFor(game: Game): DptSystem? {
    if (game.category == GameCategory.DAPHNE_NATIVE) return null
    val pack = File(game.framefilePath).parentFile?.name ?: return null
    return DptSystem.values().firstOrNull { it.packFolder.equals(pack, ignoreCase = true) }
}

fun daphneDptGames(games: List<Game>): List<Game> =
    games.filter { it.category == GameCategory.DAPHNE_NATIVE }

fun laserdiscDptGames(games: List<Game>): List<Game> =
    games.filter { it.category != GameCategory.DAPHNE_NATIVE && dptSystemFor(it) == null }

fun systemDptGames(games: List<Game>, system: DptSystem): List<Game> =
    games.filter { dptSystemFor(it) == system }

/**
 * The first card: Daphne and LaserDisc. The folders are the SAF tree URIs
 * picked on the Export page, null when not set. Written through the tree
 * URIs rather than File paths, since Handheld has no All Files Access.
 */
fun exportDptFiles(
    context: Context,
    games: List<Game>,
    daphneFolder: Uri?,
    laserdiscFolder: Uri?,
): DptExportResult = exportDptTargets(
    context,
    games,
    listOf(
        DptTarget("daphne", daphneFolder, daphneDptGames(games)),
        DptTarget("laserdisc", laserdiscFolder, laserdiscDptGames(games)),
    ),
)

/** #217 - the second card: one folder per [DptSystem]. */
fun exportSystemDptFiles(context: Context, games: List<Game>, folders: Map<DptSystem, Uri?>): DptExportResult =
    exportDptTargets(
        context,
        games,
        DptSystem.values().map { DptTarget(it.label, folders[it], systemDptGames(games, it)) },
    )

private class DptTarget(val label: String, val folder: Uri?, val games: List<Game>)

private fun exportDptTargets(context: Context, games: List<Game>, targets: List<DptTarget>): DptExportResult {
    val allNames = games.map { it.name }
    var created = 0
    var skipped = 0
    var removed = 0
    val problems = mutableListOf<String>()

    for (target in targets) {
        val label = target.label
        if (target.games.isEmpty() && target.folder == null) continue
        val folder = target.folder?.let { SafFolder.open(context, it) }
        if (folder == null) {
            problems += "$label folder not found"
            continue
        }
        val existing = folder.dptFiles()
        if (existing == null) {
            problems += "$label folder not found"
            continue
        }
        val plan = planDptFolder(target.games.map { it.name }, allNames, existing.mapValues { it.value.content })
        skipped += plan.skipped
        var failed = false
        for (name in plan.create) {
            if (folder.create("$name.$DPT_EXTENSION", dptContent(name))) {
                created++
            } else {
                failed = true
                break
            }
        }
        if (failed) {
            problems += "couldn't write to $label folder"
            continue
        }
        for (fileName in plan.remove) {
            if (folder.delete(existing.getValue(fileName).documentId)) removed++
        }
    }

    return DptExportResult(created, skipped, removed, problems)
}

private class SafFolder private constructor(
    private val context: Context,
    private val tree: Uri,
    private val rootId: String,
) {
    class DptFile(val documentId: String, val content: String?)

    companion object {
        /** Null when the folder is gone or no longer granted. */
        fun open(context: Context, tree: Uri): SafFolder? = try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val isFolder = context.contentResolver.query(
                rootUri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
            )?.use { it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR } ?: false
            if (isFolder) SafFolder(context, tree, rootId) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Every .dpt in the root by file name, or null if the folder can't be listed. */
    fun dptFiles(): Map<String, DptFile>? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            val found = mutableMapOf<String, DptFile>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (name == null || cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR || !isDptFileName(name)) {
                    continue
                }
                val size = if (cursor.isNull(3)) Long.MAX_VALUE else cursor.getLong(3)
                found[name] = DptFile(id, if (size > MAX_OWN_DPT_BYTES) null else read(id))
            }
            found
        }
    } catch (e: Exception) {
        null
    }

    // unreadable: can't prove it's ours, so it's left alone
    private fun read(documentId: String): String? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    } catch (e: Exception) {
        null
    }

    fun create(fileName: String, content: String): Boolean {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        // octet-stream so the provider keeps the name as-is instead of adding an extension
        val uri = try {
            DocumentsContract.createDocument(context.contentResolver, rootUri, "application/octet-stream", fileName)
        } catch (e: Exception) {
            null
        } ?: return false
        val written = try {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(content.toByteArray()); true } ?: false
        } catch (e: Exception) {
            false
        }
        // don't leave an empty file behind
        if (!written) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        return written
    }

    fun delete(documentId: String): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId))
    } catch (e: Exception) {
        false
    }
}
