package org.libsdl.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * #161 - Arguments Settings, the one exception to the other 4 destination
 * pages: a single full-width card, not a two-column grid. This is the one
 * page that actually needs full-screen real estate - the free-text field,
 * Add button, and full list of added arguments no longer compete for
 * leftover space against a card grid above them the way they used to on
 * the old combined Options screen. Internal layout is unchanged from
 * before, only *where* it lives changed.
 */
@Composable
fun ArgumentsSettingsScreen(
    game: Game,
    options: GameOptions,
    onAddArgument: (String) -> Unit,
    onRemoveArgument: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var argumentText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Arguments Settings: ${game.name}", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HypdroidOutlinedTextField(
                        value = argumentText,
                        onValueChange = { argumentText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("-fastboot") },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    HypdroidButton(onClick = {
                        val trimmed = argumentText.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddArgument(trimmed)
                            argumentText = ""
                        }
                    }) { Text("Add") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(options.arguments, key = { it }) { arg ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(arg, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveArgument(arg) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}
