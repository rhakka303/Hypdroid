package org.libsdl.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * #55 - Settings redesigned as a 2-column grid of category cards (modeled on
 * Eden's own Settings screen, used purely as a visual reference), each
 * opening its own dedicated screen - replaces the old single scrolling list
 * of rows, which had already needed a scroll fix once just to keep
 * Controller Configuration reachable.
 */
@Composable
fun SettingsScreen(
    onOpenManageGameFolder: () -> Unit,
    onOpenManageMediaFolder: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenControllerConfig: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTouchControls: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            val cards = listOf(
                Triple("Manage Game Folder", "Pick where your games live", onOpenManageGameFolder),
                Triple("Manage Media Folder", "Pick where your artwork lives", onOpenManageMediaFolder),
                Triple("App Settings", "Global override", onOpenAppSettings),
                Triple("Controls", "Assign gamepad buttons per action", onOpenControllerConfig),
                Triple("About", "Build info, credits, open source", onOpenAbout),
                // #83 - own dedicated screen (not an inline toggle here) so
                // there's a natural home for future control-repositioning
                // UI without needing to restructure this grid later.
                Triple("Touch Controls", "On-screen overlay for gameplay", onOpenTouchControls),
            )
            items(cards) { (title, description, onClick) ->
                SettingsCard(title = title, description = description, onClick = onClick)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, description: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Shared by both the Game folder and Media folder cards - identical shape,
 * just a different label/path/change action. `instructions`, when non-null
 * (only the Game folder screen passes it - #60), shows the required
 * singe/roms/vldp layout on-screen, since that structure is a real
 * hypseus/Singe requirement, not obvious from the folder picker alone.
 */
@Composable
fun FolderManageScreen(
    title: String,
    path: String?,
    instructions: String? = null,
    // #88 - recovery path for onboarding's own permission rows: a skipped
    // or denied permission needs somewhere to be granted later, and the
    // owner/ChatGPT design review landed on surfacing it next to the folder
    // feature it actually gates, rather than a whole new Settings category.
    // Null on whichever screen/flavor doesn't need one (e.g. always null on
    // Handheld's Manage Game Folder, since it has no All Files Access
    // concept at all).
    permissionRow: (@Composable () -> Unit)? = null,
    onChange: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(path ?: "Not set", style = MaterialTheme.typography.bodyMedium)
            }
            HypdroidButton(onClick = onChange) { Text("Change") }
        }

        if (permissionRow != null) {
            Spacer(modifier = Modifier.height(24.dp))
            permissionRow()
        }

        if (instructions != null) {
            Spacer(modifier = Modifier.height(32.dp))
            Text("Recommended Folder Structure", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(instructions, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * #47's Global Cover Art override and #66's Background Art, side by side as
 * two double-width cards - matching the main Settings grid's card style,
 * just wider since each needs room for a toggle + conditional follow-up
 * control stacked underneath.
 */
@Composable
fun AppSettingsScreen(
    globalCoverArtEnabled: Boolean,
    globalCoverArtType: CoverArtType,
    onGlobalCoverArtToggle: (Boolean) -> Unit,
    onGlobalCoverArtTypeChange: (CoverArtType) -> Unit,
    backgroundArtEnabled: Boolean,
    defaultArtEnabled: Boolean,
    onBackgroundArtToggle: (Boolean) -> Unit,
    onDefaultArtToggle: (Boolean) -> Unit,
    preserveAspectRatioEnabled: Boolean,
    onPreserveAspectRatioToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var showGlobalCoverArtPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("App Settings", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Global Cover Art", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "On: same art for every game. Off: each game picks its own.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HypdroidSwitch(checked = globalCoverArtEnabled, onCheckedChange = onGlobalCoverArtToggle)
                    }
                    if (globalCoverArtEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                globalCoverArtType.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            HypdroidButton(onClick = { showGlobalCoverArtPicker = true }) { Text("Change") }
                        }
                    }
                }
            }

            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Art", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "On: uses art from bg folder. Off: default white.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HypdroidSwitch(checked = backgroundArtEnabled, onCheckedChange = onBackgroundArtToggle)
                    }
                    // #66 - Default Art overrides every game's own bg art
                    // with bg/default.png, same override shape as Global
                    // Cover Art - only shown/meaningful while Background
                    // Art itself is on.
                    if (backgroundArtEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Default Art", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Uses default.png for all games.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HypdroidSwitch(checked = defaultArtEnabled, onCheckedChange = onDefaultArtToggle)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // #109 - off by default, matches hypseus's own existing
            // screen-fill behavior unchanged. On: real letterbox/pillarbox
            // bars for video whose native aspect ratio doesn't match the
            // screen, via a real source patch to hypseus-singe itself
            // (see docs/ANDROID_PATCHES.md), not just an Android-side crop.
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Preserve Video Aspect Ratio", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "On: video fit to screen. Off: video stretches to screen.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // #159 - clarifies this only has a visible effect on
                            // screens that aren't 16:9 (the aspect ratio, not the
                            // exact 1920x1080 resolution - a 720p or 4K 16:9 screen
                            // sees no difference either). Worth the callout since on
                            // a badly mismatched ratio (e.g. 4:3) the letterboxed
                            // video can end up quite small.
                            Text(
                                "For non-standard 16:9 screen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        HypdroidSwitch(checked = preserveAspectRatioEnabled, onCheckedChange = onPreserveAspectRatioToggle)
                    }
                }
            }
            // No second toggle to pair this with yet - an invisible spacer
            // claims the other half of the row so this card stays the same
            // size as its siblings above instead of stretching full-width.
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    if (showGlobalCoverArtPicker) {
        CoverArtPickerDialog(
            onSelect = { type ->
                onGlobalCoverArtTypeChange(type)
                showGlobalCoverArtPicker = false
            },
            onDismiss = { showGlobalCoverArtPicker = false },
        )
    }
}

/**
 * Build info, the wordmark logo, and open-source credit for the project
 * this app is built from (hypseus-singe by DirtBagXon, GPL-3.0) plus other
 * bundled dependencies - real attribution, not a game name, so naming it
 * directly is correct here (unlike copyrighted game titles elsewhere).
 */
@Composable
fun AboutScreen(context: Context, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("About", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Image(
            painter = painterResource(id = R.drawable.hypdroid_logo),
            contentDescription = "Hypdroid",
            modifier = Modifier.height(64.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)

        Spacer(modifier = Modifier.height(32.dp))

        Text("Open Source", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Built on Hypseus Singe by DirtBagXon (github.com/DirtBagXon/hypseus-singe), " +
                "licensed under GPL-3.0.\n\n" +
                "Also uses SDL3, Coil, and RadialGamePad.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * #83 - own dedicated screen (not an inline toggle on the Settings grid), so
 * a future "Configure" button for custom control repositioning (explicitly
 * out of scope for this issue - see its own comment thread) has a natural
 * home without restructuring anything later. Stick Mode only shown/relevant
 * once Touch Controls itself is on, same conditional-visibility pattern as
 * Background Art's own Default Art sub-toggle.
 */
@Composable
fun TouchControlsScreen(
    touchControlsEnabled: Boolean,
    stickModeEnabled: Boolean,
    opacity: Float,
    onTouchControlsToggle: (Boolean) -> Unit,
    onStickModeToggle: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Touch Controls", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Touch Controls", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Shows an on-screen gamepad overlay during gameplay.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HypdroidSwitch(checked = touchControlsEnabled, onCheckedChange = onTouchControlsToggle)
                    }
                    if (touchControlsEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Stick Mode", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Off: D-pad. On: virtual thumbstick.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HypdroidSwitch(checked = stickModeEnabled, onCheckedChange = onStickModeToggle)
                        }
                    }
                }
            }

            OutlinedCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "How see-through the touch controls are during gameplay.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(value = opacity, onValueChange = onOpacityChange)
                    Text(
                        "${(opacity * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (touchControlsEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            TouchControlsPreview(opacity = opacity)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Preview only. A rough guide, not an exact match to the real in-game layout.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Non-interactive mockup (D-pad + Y/X/B/A only) so the owner can judge the
 * Appearance slider's effect before it's actually applied in-game. Drawn
 * over a fixed neutral-gray backdrop, standing in for a typical (moderately
 * dark) gameplay scene, rather than the plain Settings background - a real
 * game frame varies too much per-game to represent exactly, but a flat
 * white background would make every opacity look more visible than it
 * really is.
 */
@Composable
private fun TouchControlsPreview(opacity: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF4A4A4A)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .alpha(opacity),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MockDpad()
            MockFaceButtons()
        }
    }
}

@Composable
private fun MockDpad() {
    Canvas(modifier = Modifier.size(100.dp)) {
        drawCircle(color = Color(0x552B2B2B), radius = size.minDimension / 2f)
        val armLength = size.minDimension * 0.34f
        val armThickness = size.minDimension * 0.22f
        val center = Offset(size.width / 2f, size.height / 2f)
        val corner = CornerRadius(8f, 8f)
        // Vertical arm of the D-pad cross.
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(center.x - armThickness / 2f, center.y - armLength),
            size = Size(armThickness, armLength * 2f),
            cornerRadius = corner,
        )
        // Horizontal arm.
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(center.x - armLength, center.y - armThickness / 2f),
            size = Size(armLength * 2f, armThickness),
            cornerRadius = corner,
        )
    }
}

@Composable
private fun MockFaceButtons() {
    Box(modifier = Modifier.size(100.dp)) {
        MockButtonCircle("Y", Modifier.align(Alignment.TopCenter))
        MockButtonCircle("X", Modifier.align(Alignment.CenterStart))
        MockButtonCircle("B", Modifier.align(Alignment.CenterEnd))
        MockButtonCircle("A", Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MockButtonCircle(label: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xFF2B2B2B)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
