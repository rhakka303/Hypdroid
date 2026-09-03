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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * #161 - Bezel Settings, one of the 5 destination pages the Options screen
 * now taps through to. Folds in Scorebezel Autofit as its own card, same
 * two-column grid every non-Arguments destination page uses. Overlay Bezel
 * stays nested inside the Bezel card exactly as before (#117/#124) - it's
 * conditional on Bezel itself being on, not a standalone feature, so it
 * doesn't get promoted to its own card.
 */
@Composable
fun BezelSettingsScreen(
    game: Game,
    options: GameOptions,
    onBezelToggle: (Boolean) -> Unit,
    onScorebezelAutofitToggle: (Boolean) -> Unit,
    onOverlayBezelToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Bezel Settings: ${game.name}", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bezel", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (options.bezelEnabled) "On" else "Off",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HypdroidSwitch(checked = options.bezelEnabled, onCheckedChange = onBezelToggle)
                    }

                    // #117 - redraws whatever a game draws via spriteDraw
                    // (score/lives/skip icon/move arrows) a second time on
                    // top of custom bezel art, since it's otherwise buried
                    // under the bezel. Only shown/meaningful while Bezel
                    // itself is on (#124) - same conditional-visibility
                    // pattern as Background Art's own Default Art
                    // sub-toggle. There's nothing to draw the overlay on
                    // top of otherwise, and doing so anyway produced
                    // visible ghosting on real hardware.
                    if (options.bezelEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Overlay Bezel", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (options.overlayBezel) "On" else "Off",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Makes overlays a priority",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            HypdroidSwitch(
                                checked = options.overlayBezel,
                                onCheckedChange = onOverlayBezelToggle,
                            )
                        }
                    }
                }
            }

            // #111 - fits the scoreboard bezel (score/lives/credits panel)
            // to the real black-bar space next to the video instead of a
            // fixed fraction of the video's own width. Independent of
            // Bezel above - matters for any game with a scoreboard bezel
            // active, whether that's this Bezel toggle or the game's own
            // script turning its scoreboard bezel on directly (like Esh's
            // Aurunmilla does).
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scorebezel Autofit", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (options.scorebezelAutofit) {
                                    "On: fits width of pillarbox bars."
                                } else {
                                    "Off: original size."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HypdroidSwitch(
                            checked = options.scorebezelAutofit,
                            onCheckedChange = onScorebezelAutofitToggle,
                        )
                    }
                }
            }
        }
    }
}
