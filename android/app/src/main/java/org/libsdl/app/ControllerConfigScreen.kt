package org.libsdl.app

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.File

private const val AXIS_THRESHOLD = 0.5f

// Most gamepad buttons arrive as discrete KeyEvents. A few controllers also
// report the analog triggers as a discrete digital press (KEYCODE_BUTTON_L2/
// R2) in addition to a continuous MotionEvent axis - both paths are handled,
// since which one a given controller actually uses can't be assumed without
// testing real hardware.
private fun mapKeyEventToButtonToken(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> "BUTTON_A"
    KeyEvent.KEYCODE_BUTTON_B -> "BUTTON_B"
    KeyEvent.KEYCODE_BUTTON_X -> "BUTTON_X"
    KeyEvent.KEYCODE_BUTTON_Y -> "BUTTON_Y"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "BUTTON_BACK"
    KeyEvent.KEYCODE_BUTTON_MODE -> "BUTTON_GUIDE"
    KeyEvent.KEYCODE_BUTTON_START -> "BUTTON_START"
    KeyEvent.KEYCODE_BUTTON_THUMBL -> "BUTTON_LEFTSTICK"
    KeyEvent.KEYCODE_BUTTON_THUMBR -> "BUTTON_RIGHTSTICK"
    KeyEvent.KEYCODE_BUTTON_L1 -> "BUTTON_LEFTSHOULDER"
    KeyEvent.KEYCODE_BUTTON_R1 -> "BUTTON_RIGHTSHOULDER"
    KeyEvent.KEYCODE_DPAD_UP -> "BUTTON_DPAD_UP"
    KeyEvent.KEYCODE_DPAD_DOWN -> "BUTTON_DPAD_DOWN"
    KeyEvent.KEYCODE_DPAD_LEFT -> "BUTTON_DPAD_LEFT"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "BUTTON_DPAD_RIGHT"
    KeyEvent.KEYCODE_BUTTON_L2 -> "AXIS_TRIGGER_LEFT"
    KeyEvent.KEYCODE_BUTTON_R2 -> "AXIS_TRIGGER_RIGHT"
    else -> null
}

// Left/right stick pushed past the threshold, for the AxisPad0 slot.
private fun mapMotionEventToAxisToken(event: MotionEvent): String? {
    val y = event.getAxisValue(MotionEvent.AXIS_Y)
    if (y < -AXIS_THRESHOLD) return "AXIS_LEFT_UP"
    if (y > AXIS_THRESHOLD) return "AXIS_LEFT_DOWN"
    val x = event.getAxisValue(MotionEvent.AXIS_X)
    if (x < -AXIS_THRESHOLD) return "AXIS_LEFT_LEFT"
    if (x > AXIS_THRESHOLD) return "AXIS_LEFT_RIGHT"
    val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
    if (rz < -AXIS_THRESHOLD) return "AXIS_RIGHT_UP"
    if (rz > AXIS_THRESHOLD) return "AXIS_RIGHT_DOWN"
    val z = event.getAxisValue(MotionEvent.AXIS_Z)
    if (z < -AXIS_THRESHOLD) return "AXIS_RIGHT_LEFT"
    if (z > AXIS_THRESHOLD) return "AXIS_RIGHT_RIGHT"
    return null
}

// Analog trigger pulled past the threshold, for the Pad0Button slot (see
// GamepadIni.kt - triggers are parsed as "buttons" by hypseus, not axes).
// AXIS_LTRIGGER/RTRIGGER is the standard mapping; AXIS_BRAKE/GAS is a
// fallback some controllers use instead.
private fun mapMotionEventToTriggerToken(event: MotionEvent): String? {
    val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).let {
        if (it == 0f) event.getAxisValue(MotionEvent.AXIS_BRAKE) else it
    }
    if (lt > AXIS_THRESHOLD) return "AXIS_TRIGGER_LEFT"
    val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).let {
        if (it == 0f) event.getAxisValue(MotionEvent.AXIS_GAS) else it
    }
    if (rt > AXIS_THRESHOLD) return "AXIS_TRIGGER_RIGHT"
    return null
}

fun captureTokenForKeyEvent(event: KeyEvent, listeningForAxis: Boolean): String? {
    if (listeningForAxis || event.action != KeyEvent.ACTION_DOWN) return null
    return mapKeyEventToButtonToken(event.keyCode)
}

fun captureTokenForMotionEvent(event: MotionEvent, listeningForAxis: Boolean): String? {
    if (event.action != MotionEvent.ACTION_MOVE) return null
    return if (listeningForAxis) mapMotionEventToAxisToken(event) else mapMotionEventToTriggerToken(event)
}

private fun findConflict(rows: List<GamepadRow>, keyName: String, slot: BindingSlot, token: String): String? {
    if (token == "0") return null
    for (row in rows) {
        if (row.keyName == keyName) continue
        val existing = when (slot) {
            BindingSlot.PAD0_BUTTON -> row.pad0Button
            BindingSlot.AXIS_PAD0 -> row.axisPad0
        }
        if (existing == token) return row.keyName
    }
    return null
}

private fun withBinding(rows: List<GamepadRow>, keyName: String, slot: BindingSlot, token: String): List<GamepadRow> =
    rows.map { row ->
        if (row.keyName != keyName) {
            row
        } else {
            when (slot) {
                BindingSlot.PAD0_BUTTON -> row.copy(pad0Button = token)
                BindingSlot.AXIS_PAD0 -> row.copy(axisPad0 = token)
            }
        }
    }

private data class ConflictState(
    val keyName: String,
    val slot: BindingSlot,
    val token: String,
    val conflictingKeyName: String,
)

/**
 * #41 - live gamepad button mapping. Reads/writes the one real
 * hypinput_gamepad.ini inside the Game folder (gamepadIniPath(), see
 * GamepadIni.kt) - #60 made -datadir always the Game folder itself for
 * every category, so there's only ever one file now (#72 fixed this screen
 * to actually agree with that after #60 landed).
 *
 * Capture works through Android's own KeyEvent/MotionEvent APIs, not
 * hypseus/SDL - this screen is plain Compose in MainActivity, which never
 * runs SDL at all (SDL only exists inside HypseusActivity during actual
 * gameplay). MainActivity.dispatchKeyEvent()/dispatchGenericMotionEvent()
 * forward real input events here via gamepadCaptureListener while a slot is
 * being listened to.
 */
@Composable
fun ControllerConfigScreen(
    activity: MainActivity,
    gameFolderPath: String,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var rows by remember { mutableStateOf<List<GamepadRow>?>(null) }
    var fileMissing by remember { mutableStateOf(false) }

    LaunchedEffect(gameFolderPath) {
        val file = File(gamepadIniPath(gameFolderPath))
        if (file.exists()) {
            rows = parseGamepadRows(file.readText())
            fileMissing = false
        } else {
            fileMissing = true
        }
    }

    var listening by remember { mutableStateOf<Pair<String, BindingSlot>?>(null) }
    var conflict by remember { mutableStateOf<ConflictState?>(null) }
    // #84 - a touch-only user has no physical controller to press, so the
    // existing "listening" capture flow above never resolves for them.
    // pickerFor tracks which row/slot has its list picker open - a fully
    // separate action from listening, wired to the exact same
    // conflict-check/apply pipeline via resolveToken() below so a token
    // chosen from the list is validated identically to one captured from a
    // real press. Never touches `listening` or gamepadCaptureListener, so
    // Handheld's existing press-to-capture flow is unaffected either way.
    var pickerFor by remember { mutableStateOf<Pair<String, BindingSlot>?>(null) }

    fun resolveToken(keyName: String, slot: BindingSlot, token: String) {
        val currentRows = rows ?: return
        val conflictingKey = findConflict(currentRows, keyName, slot, token)
        if (conflictingKey != null) {
            conflict = ConflictState(keyName, slot, token, conflictingKey)
        } else {
            applyBinding(gameFolderPath, keyName, slot, token)
            rows = withBinding(currentRows, keyName, slot, token)
        }
    }

    DisposableEffect(listening) {
        val current = listening
        if (current == null) {
            activity.gamepadCaptureListener = null
        } else {
            val (keyName, slot) = current
            activity.gamepadCaptureListeningForAxis = (slot == BindingSlot.AXIS_PAD0)
            activity.gamepadCaptureListener = { token ->
                resolveToken(keyName, slot, token)
                listening = null
            }
        }
        onDispose { activity.gamepadCaptureListener = null }
    }

    // #159 - one universal pill width for both the Button and Axis columns,
    // instead of each pill auto-sizing to its own label so nothing lined up
    // between rows. Measured from every *possible* label (not just what's
    // currently assigned), so the width doesn't jump around as bindings
    // change - includes both token lists and the two listening-state
    // labels. ButtonDefaults' own horizontal content padding (24.dp each
    // side, unset/default here) is added on top of the raw text width,
    // since HypdroidButton doesn't override it.
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelLarge
    val pillWidth: Dp = remember(labelStyle) {
        val candidates = VALID_BUTTON_TOKENS.map { "Button: $it" } +
            VALID_AXIS_TOKENS.map { "Axis: $it" } +
            listOf("Press a button...", "Move a stick...")
        val widestPx = candidates.maxOf { textMeasurer.measure(it, labelStyle).size.width }
        with(density) { widestPx.toDp() } + 48.dp
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Controller Configuration", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(16.dp))

        val currentRows = rows
        if (fileMissing) {
            Text("Launch a game once first to generate the default controller settings.")
        } else if (currentRows == null) {
            Text("Loading...")
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(currentRows, key = { it.keyName }) { row ->
                    ControllerRow(
                        row = row,
                        pillWidth = pillWidth,
                        isListeningButton = listening == (row.keyName to BindingSlot.PAD0_BUTTON),
                        isListeningAxis = listening == (row.keyName to BindingSlot.AXIS_PAD0),
                        onTapButton = { listening = row.keyName to BindingSlot.PAD0_BUTTON },
                        onTapAxis = { listening = row.keyName to BindingSlot.AXIS_PAD0 },
                        onOpenButtonPicker = { pickerFor = row.keyName to BindingSlot.PAD0_BUTTON },
                        onOpenAxisPicker = { pickerFor = row.keyName to BindingSlot.AXIS_PAD0 },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }

    pickerFor?.let { (keyName, slot) ->
        val row = rows?.find { it.keyName == keyName }
        val currentToken = when {
            row == null -> null
            slot == BindingSlot.PAD0_BUTTON -> row.pad0Button
            else -> row.axisPad0
        }
        TokenPickerDialog(
            title = "Choose a binding for $keyName",
            tokens = if (slot == BindingSlot.PAD0_BUTTON) VALID_BUTTON_TOKENS else VALID_AXIS_TOKENS,
            currentToken = currentToken,
            onSelect = { token ->
                resolveToken(keyName, slot, token)
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }

    conflict?.let { c ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Already assigned") },
            text = {
                Text("${c.token} is currently assigned to ${c.conflictingKeyName} too. Assign it to ${c.keyName} as well?")
            },
            confirmButton = {
                TextButton(onClick = {
                    applyBinding(gameFolderPath, c.keyName, c.slot, c.token)
                    rows = withBinding(rows ?: emptyList(), c.keyName, c.slot, c.token)
                    conflict = null
                }) { Text("Assign anyway") }
            },
            dismissButton = {
                TextButton(onClick = { conflict = null }) { Text("Cancel") }
            },
        )
    }
}

// Only the 4 directional rows use AxisPad0 in the real file (a left-stick
// push as a redundant alternative to the d-pad button) - every other row
// only ever has a Pad0Button, so there's nothing meaningful to show or
// capture for an Axis slot there.
private val DIRECTIONAL_KEYS = setOf("KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT")

// #84/#92 - the app-wide HypdroidButton (Theme.kt) now carries this same
// focus ring for every filled button, not just this screen's own capture
// buttons - kept as a thin alias here since ControllerRow already refers
// to "CaptureButton" by name and there's no reason to churn that.
@Composable
private fun CaptureButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    HypdroidButton(onClick = onClick, modifier = modifier, content = content)

@Composable
private fun ControllerRow(
    row: GamepadRow,
    pillWidth: Dp,
    isListeningButton: Boolean,
    isListeningAxis: Boolean,
    onTapButton: () -> Unit,
    onTapAxis: () -> Unit,
    onOpenButtonPicker: () -> Unit,
    onOpenAxisPicker: () -> Unit,
) {
    Column {
        Text(row.keyName, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CaptureButton(onClick = onTapButton, modifier = Modifier.width(pillWidth)) {
                Text(if (isListeningButton) "Press a button..." else "Button: ${displayToken(row.pad0Button)}")
            }
            // #84 - a fully separate action from onTapButton above, never
            // touching the press-to-capture flow - opens a list of the same
            // token vocabulary so a touch-only user (no physical controller
            // to press) can still assign a binding. Excluded from focus
            // traversal (canFocus = false) - Handheld already has a fully
            // working D-pad flow across just the capture buttons (confirmed
            // on a real device: Up/Down highlights each one, A activates
            // it), and IconButton is focusable by default, which would add
            // these as extra D-pad stops nobody there needs - still tappable
            // by touch either way, focusability doesn't gate that.
            IconButton(
                onClick = onOpenButtonPicker,
                modifier = Modifier.focusProperties { canFocus = false },
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose Button from a list")
            }
            if (row.keyName in DIRECTIONAL_KEYS) {
                CaptureButton(onClick = onTapAxis, modifier = Modifier.width(pillWidth)) {
                    Text(if (isListeningAxis) "Move a stick..." else "Axis: ${displayToken(row.axisPad0)}")
                }
                IconButton(
                    onClick = onOpenAxisPicker,
                    modifier = Modifier.focusProperties { canFocus = false },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose Axis from a list")
                }
            }
        }
    }
}

private fun displayToken(token: String): String = if (token == "0") "None" else token

/**
 * #84 - the list-based alternative to physical-press capture, for a
 * touch-only user with no controller to press. Touch-only: no focus
 * request or onKeyEvent handling here - an earlier version requested focus
 * on the Column wrapping the LazyColumn to support D-pad navigation, but
 * that fought the list's own manual scroll gestures and made it feel stuck
 * (confirmed on a real device). Tapping a token applies it immediately and
 * closes the dialog; the already-bound token is highlighted and scrolled
 * into view up front so it's obvious what's currently set.
 */
@Composable
private fun TokenPickerDialog(
    title: String,
    tokens: List<String>,
    currentToken: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialIndex = remember(currentToken) { tokens.indexOf(currentToken).coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 400.dp)) {
                    items(tokens) { token ->
                        val isCurrent = token == currentToken
                        TextButton(
                            onClick = { onSelect(token) },
                            colors = if (isCurrent) {
                                ButtonDefaults.textButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                ButtonDefaults.textButtonColors()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(token, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}
