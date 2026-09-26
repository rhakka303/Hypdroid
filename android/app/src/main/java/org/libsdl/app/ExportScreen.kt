package org.libsdl.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_DAPHNE_DPT_FOLDER_URI = "daphne_dpt_folder_uri"
private const val PREF_LASERDISC_DPT_FOLDER_URI = "laserdisc_dpt_folder_uri"

// #217 - the second card's folders, one per pack system.
private fun systemFolderPref(system: DptSystem) = "${system.label}_dpt_folder_uri"

private val DptSystem.folderLabel: String
    get() = when (this) {
        DptSystem.ACTION_MAX -> "Action Max folder"
        DptSystem.VIDEO_DRIVER -> "Video Driver folder"
        DptSystem.CAPTAIN_POWER -> "Captain Power folder"
    }

/**
 * #211 - Settings' Export page, two "Create DPT Files" cards side by side
 * (see DptExport.kt). Left: Daphne and LaserDisc. Right (#217): the Action
 * Max, Video Driver and Captain Power packs, each to its own folder.
 *
 * The folders are picked here, not derived from the game folder: Android
 * only lets Hypdroid write to folders the user chose.
 */
@Composable
fun ExportScreen(gameFolderChosen: Boolean, games: List<Game>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daphneFolder by remember { mutableStateOf(loadPersistedFolderUri(context, PREF_DAPHNE_DPT_FOLDER_URI)) }
    var laserdiscFolder by remember { mutableStateOf(loadPersistedFolderUri(context, PREF_LASERDISC_DPT_FOLDER_URI)) }
    var systemFolders by remember {
        mutableStateOf(DptSystem.values().associateWith { loadPersistedFolderUri(context, systemFolderPref(it)) })
    }
    // one export at a time: 0 = first card, 1 = second card, null = idle
    var runningCard by remember { mutableStateOf<Int?>(null) }
    var mainResult by remember { mutableStateOf<String?>(null) }
    var systemResult by remember { mutableStateOf<String?>(null) }

    fun folderPicker(key: String, onPicked: (Uri) -> Unit) = { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            savePersistedFolderUri(context, key, uri)
            onPicked(uri)
        }
    }
    val pickDaphne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(),
        folderPicker(PREF_DAPHNE_DPT_FOLDER_URI) { daphneFolder = it })
    val pickLaserdisc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(),
        folderPicker(PREF_LASERDISC_DPT_FOLDER_URI) { laserdiscFolder = it })
    val systemPickers = DptSystem.values().associateWith { system ->
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(),
            folderPicker(systemFolderPref(system)) { systemFolders = systemFolders + (system to it) })
    }

    // #211 - also needs games: with an empty list every one of our files
    // would look stale, so a failed scan could otherwise remove them all.
    val canExport = gameFolderChosen && games.isNotEmpty() && runningCard == null

    fun runExport(card: Int, onResult: (String?) -> Unit, export: () -> DptExportResult) {
        runningCard = card
        onResult(null)
        scope.launch {
            val result = withContext(Dispatchers.IO) { export() }
            onResult(dptResultMessage(result))
            runningCard = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Export", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DptCard(
                subtitle = "For frontends like Daijishō and ES-DE",
                enabled = canExport,
                creating = runningCard == 0,
                result = mainResult,
                onCreate = {
                    runExport(0, { mainResult = it }) { exportDptFiles(context, games, daphneFolder, laserdiscFolder) }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                ExportFolderRow("Daphne folder", daphneFolder) { pickDaphne.launch(null) }
                Spacer(modifier = Modifier.height(8.dp))
                ExportFolderRow("LaserDisc folder", laserdiscFolder) { pickLaserdisc.launch(null) }
            }
            DptCard(
                subtitle = "Action Max, Video Driver and Captain Power",
                enabled = canExport,
                creating = runningCard == 1,
                result = systemResult,
                onCreate = {
                    runExport(1, { systemResult = it }) { exportSystemDptFiles(context, games, systemFolders) }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                DptSystem.values().forEachIndexed { index, system ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    ExportFolderRow(system.folderLabel, systemFolders[system]) { systemPickers.getValue(system).launch(null) }
                }
            }
        }
    }
}

@Composable
private fun DptCard(
    subtitle: String,
    enabled: Boolean,
    creating: Boolean,
    result: String?,
    onCreate: () -> Unit,
    modifier: Modifier,
    folders: @Composable () -> Unit,
) {
    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Create DPT Files", style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(12.dp))
            folders()

            Spacer(modifier = Modifier.height(12.dp))
            HypdroidButton(enabled = enabled, onClick = onCreate) {
                Text(if (creating) "Creating..." else "Create DPT Files")
            }

            if (result != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ExportFolderRow(label: String, folder: Uri?, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            val shown = when {
                folder == null -> "Not set"
                else -> resolveRealPath(folder) ?: "Not found"
            }
            Text(shown, style = MaterialTheme.typography.bodySmall)
        }
        HypdroidButton(onClick = onChange) { Text("Change") }
    }
}
