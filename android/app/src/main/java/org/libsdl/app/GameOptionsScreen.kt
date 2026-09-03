package org.libsdl.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * #161 - redesigned as 5 short summary cards, each tapping through to its
 * own dedicated page, matching the pattern the Settings screen already
 * uses (SettingsScreens.kt). Previously this screen held every control
 * inline, which meant Arguments - the one thing that actually needed real
 * room - was squeezed into whatever vertical space was left over after the
 * other cards, forcing users to scroll to see their own added arguments.
 *
 * Cover Art / Bezel / Game Hacks / Video Snaps / Arguments, in that order,
 * as a 2-column grid (3 rows, last one half-empty) - same shape Settings
 * uses for its own 6 cards.
 */
@Composable
fun GameOptionsScreen(
    game: Game,
    options: GameOptions,
    onOpenCoverArtSettings: () -> Unit,
    onOpenBezelSettings: () -> Unit,
    onOpenGameHack: () -> Unit,
    onOpenArgumentsSettings: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Options: ${game.name}", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionsSummaryCard(
                title = "Cover Art Settings",
                subtitle = (options.coverArt ?: CoverArtType.BOX).name,
                onClick = onOpenCoverArtSettings,
                modifier = Modifier.weight(1f),
            )
            OptionsSummaryCard(
                title = "Bezel Settings",
                subtitle = if (options.bezelEnabled) "On" else "Off",
                onClick = onOpenBezelSettings,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionsSummaryCard(
                title = "Game Hacks Settings",
                subtitle = "Custom game fixes",
                onClick = onOpenGameHack,
                modifier = Modifier.weight(1f),
            )
            // #161 - placeholder for the not-yet-built video-attract-preview
            // feature (see the brainstorm notes, not scoped here). Exists so
            // the 5-card grid is complete and consistent now; deliberately
            // non-interactive since there's no real destination yet. A
            // future issue renames this card and builds the real page.
            OptionsSummaryCard(
                title = "Future Place Holder",
                subtitle = null,
                onClick = null,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionsSummaryCard(
                title = "Arguments Settings",
                subtitle = if (options.arguments.isEmpty()) "None added" else "${options.arguments.size} added",
                onClick = onOpenArgumentsSettings,
                modifier = Modifier.weight(1f),
            )
            // Intentionally empty - 5 cards in a 2-column grid always
            // leaves the last row half-full, same convention every other
            // destination page in this redesign uses.
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun OptionsSummaryCard(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth().let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The 4-choice (CD/Logo/Box/Text) Cover Art picker, shared between the
 * per-game Cover Art Settings page (CoverArtSettingsScreen.kt) and
 * Settings' own "Global Cover Art" row (SettingsScreens.kt) - same list,
 * same behavior, just a different caller for what "selecting a type" means.
 */
@Composable
fun CoverArtPickerDialog(onSelect: (CoverArtType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cover Art") },
        text = {
            Column {
                CoverArtType.entries.forEach { type ->
                    Text(
                        type.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(type) }
                            .padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
