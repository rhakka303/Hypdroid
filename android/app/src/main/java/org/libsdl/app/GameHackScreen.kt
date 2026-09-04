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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * #135/#137 - opened from the per-game Options screen's "Game Hacks" card,
 * same push-navigation shell as Settings > Controls.
 */
@Composable
fun GameHackScreen(
    game: Game,
    options: GameOptions,
    onAspectBezelFixToggle: (Boolean) -> Unit,
    onReduceAttractVideoResolutionToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Game Hacks: ${game.name}", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // #137 - explicit per-game opt-in for a bezel sized to match the
            // video's own resolution, only meaningful alongside the
            // separate, global Preserve Aspect Ratio setting.
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Aspect Ratio Bezel Fix", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Matches this game's bezel to the video - only confirmed on one gun game so far",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HypdroidSwitch(
                            checked = options.aspectBezelFix,
                            onCheckedChange = onAspectBezelFixToggle,
                        )
                    }
                }
            }

            // #183 - scoped only to the attract preview (MpvPlayerView),
            // not real gameplay - a completely separate hypseus-native
            // pipeline this has no effect on. Post-decode scale-down
            // (aspect-preserving, not a fixed 1280x720) for performance -
            // see MpvPlayerView.kt for why "720p" isn't a literal promise
            // for non-16:9 sources.
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reduce Attract Video Resolution", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Scales down the attract preview for performance - attract video only, not real gameplay",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HypdroidSwitch(
                            checked = options.reduceAttractVideoResolution,
                            onCheckedChange = onReduceAttractVideoResolutionToggle,
                        )
                    }
                }
            }
        }
    }
}
