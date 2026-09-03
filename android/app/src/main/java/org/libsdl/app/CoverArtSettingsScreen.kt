package org.libsdl.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * #161 - Cover Art Settings, one of the 5 destination pages the Options
 * screen now taps through to. Same two-column card grid every non-Arguments
 * destination page uses, even though there's only one real card's worth of
 * content here - the second column stays intentionally empty, same
 * convention GameHackScreen already established.
 */
@Composable
fun CoverArtSettingsScreen(
    game: Game,
    options: GameOptions,
    globalCoverArtEnabled: Boolean,
    onCoverArtChange: (CoverArtType) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var showCoverArtPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Cover Art Settings: ${game.name}", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Cover Art",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        HypdroidButton(
                            onClick = { showCoverArtPicker = true },
                            enabled = !globalCoverArtEnabled,
                        ) { Text("Change") }
                    }
                    Text(
                        (options.coverArt ?: CoverArtType.BOX).name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // #47 - while the Settings "Global Cover Art" override is
                    // on, every game shows that single chosen type
                    // regardless of what's saved here, so changing it here
                    // would have no visible effect. Greyed out rather than
                    // hidden - the per-game choice underneath is still
                    // there, just not applied, and picking it back up when
                    // Global is turned off shouldn't require re-entering it.
                    if (globalCoverArtEnabled) {
                        Text(
                            "Controlled by Settings > App Settings > Global Cover Art",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // #161 - intentionally empty second column, same convention
            // GameHackScreen already established for a single-card page.
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {}
            }
        }
    }

    if (showCoverArtPicker) {
        CoverArtPickerDialog(
            onSelect = { type ->
                onCoverArtChange(type)
                showCoverArtPicker = false
            },
            onDismiss = { showCoverArtPicker = false },
        )
    }
}
