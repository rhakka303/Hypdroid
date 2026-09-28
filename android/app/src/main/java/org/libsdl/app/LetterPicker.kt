package org.libsdl.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * #209 - the "Sort" picker: "#" then A to Z in a compact grid. Choosing an
 * entry jumps the carousel to the first game that starts with it (see
 * LetterJump.kt); a letter with no games is dimmed and can't be chosen (not
 * focusable either, so the D-pad skips it). Opens with focus on the letter
 * of the game currently centred, so on a gamepad it's one press to confirm
 * or a few to move. Back/B or Cancel closes it without moving.
 *
 * #219 - on 1920x1080 handhelds Android's default dialog width came out too
 * narrow for 9 letters a row, cutting off the last three columns. The dialog
 * now sizes to its content instead (Material caps it at 560dp).
 */
@Composable
fun LetterPickerDialog(
    current: String,
    targets: Map<String, Int>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Sort") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LETTER_ENTRIES.chunked(9).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { entry ->
                            LetterCell(
                                entry = entry,
                                enabled = entry in targets,
                                selected = entry == current,
                                focusRequester = if (entry == current) currentRequester else null,
                                onClick = { onSelect(entry) },
                            )
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                // current always has a game (it's the centred one's letter);
                // if focus can't be placed yet, the D-pad still reaches the grid.
                runCatching { currentRequester.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LetterCell(
    entry: String,
    enabled: Boolean,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surfaceVariant)
            .then(if (isFocused) Modifier.border(3.dp, HypdroidGreenAccent, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            entry,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.3f),
        )
    }
}
