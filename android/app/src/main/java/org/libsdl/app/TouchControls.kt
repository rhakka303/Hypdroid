package org.libsdl.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadTheme
import com.swordfish.radialgamepad.library.event.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min

// #83 - Settings persistence for the touch overlay, same PREFS_NAME/pattern
// as GameOptions.kt's other app-wide toggles (Background Art, Global Cover
// Art). Visible/usable on both flavors by the owner's explicit call (issue
// #83 comment, 2026-08-20) - only testing/validation is Touch-only.
private const val PREF_TOUCH_CONTROLS_ENABLED = "touch_controls_enabled"
private const val PREF_TOUCH_CONTROLS_STICK_MODE = "touch_controls_stick_mode"

fun loadTouchControlsEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_TOUCH_CONTROLS_ENABLED, false)
}

fun saveTouchControlsEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_TOUCH_CONTROLS_ENABLED, enabled)
        .apply()
}

// D-pad (default) vs a virtual thumbstick - the swap called for in #83.
fun loadTouchControlsStickMode(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_TOUCH_CONTROLS_STICK_MODE, false)
}

fun saveTouchControlsStickMode(context: Context, stickMode: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_TOUCH_CONTROLS_STICK_MODE, stickMode)
        .apply()
}

// 0f (fully invisible) .. 1f (fully opaque) - owner-tunable overlay
// visibility, previewed live in TouchControlsScreen and applied for real
// here at the next game launch.
private const val PREF_TOUCH_CONTROLS_OPACITY = "touch_controls_opacity"

fun loadTouchControlsOpacity(context: Context): Float {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getFloat(PREF_TOUCH_CONTROLS_OPACITY, 0.5f)
}

fun saveTouchControlsOpacity(context: Context, opacity: Float) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_TOUCH_CONTROLS_OPACITY, opacity)
        .apply()
}

/**
 * #83 - Bridges touch events into the exact same native SDL entry points a
 * real physical controller's key/motion events already go through
 * (SDLControllerManager.onNativePadDown/onNativePadUp/onNativeJoy - see
 * SDLActivity.dispatchKeyEvent/handleJoystickMotionEvent for the real-
 * hardware equivalents this mirrors). This makes the overlay indistinguishable
 * from a second physical controller to hypseus, so it automatically honors
 * whatever's already in hypinput_gamepad.ini (Pad0Button/AxisPad0) - no
 * changes needed to GamepadIni.kt or the .ini file itself.
 *
 * Registering as a real joystick (rather than calling onNativePadDown with an
 * unregistered device id) is required, not just tidy: Android_OnPadDown in
 * SDL's vendored source (sdl/src/joystick/android/SDL_sysjoystick.c) silently
 * reroutes button events to SDL_SendKeyboardKey() instead of a gamepad button
 * when the device id isn't a known joystick - which would never match the
 * owner's real Pad0Button/AxisPad0 bindings.
 *
 * Triggers (L2/R2) are deliberately NOT sent via onNativePadDown/Up - hypseus's
 * own keycodes.cpp/input.cpp parse AXIS_TRIGGER_LEFT/RIGHT as a thresholded
 * analog axis read (input.cpp's "Deal with AXIS TRIGGERS" block), not a
 * button state - confirmed by reading that code, not assumed. So they're
 * routed through onNativeJoy like a real analog trigger would be.
 *
 * Layout (2026-08-20 rework, PPSSPP-referenced per owner feedback - the
 * original two-RadialGamePad-cluster layout packed L1/L2 right against the
 * D-pad, small enough that a real thumb covered all three at once):
 * - D-pad and A/B/X/Y are each their own RadialGamePad, no secondary dials -
 *   vertically centered on the left/right edges (same height, higher up than
 *   the old bottom-corner placement), sized as a fraction of screen height
 *   instead of a fixed dp value so they scale with the actual device.
 * - L1/L2/R1/R2 are plain rectangular Buttons pinned to the top corners,
 *   fully separated from the D-pad/face-button clusters.
 * - SELECT/START are plain Buttons centered at the bottom.
 */
class TouchOverlay(private val activity: Activity) {

    // Not a real Android input device id (those are always non-negative per
    // InputDevice.getId()'s contract) - this only needs to be a stable key
    // into SDL's own device_id->joystick lookup table, guaranteed not to
    // collide with any real controller also connected at the same time.
    private val deviceId = -1000

    // Registered as a 6-axis pad (LEFTX, LEFTY, RIGHTX/Y unused, then the two
    // triggers) purely so the trigger axes land at indices 4/5 - the same
    // positional convention SDLControllerManager.getAxisMask() documents for
    // real 6-axis Android gamepads. RIGHTX/RIGHTY are never actually sent.
    private val axisLeftX = 0
    private val axisLeftY = 1
    private val axisTriggerLeft = 4
    private val axisTriggerRight = 5

    private var scope: CoroutineScope? = null
    private val addedViews = mutableListOf<View>()
    private var activeDpadDirections: Set<Int> = emptySet()

    private val dpadStickId = 0

    /**
     * Only does anything if the Settings toggle is on (or minimalOnly is
     * true) - a no-op otherwise.
     *
     * #85 - these three values are passed in from the launching Intent
     * rather than read from SharedPreferences here. HypseusActivity now
     * runs in its own `:hypseus` process (see AndroidManifest.xml), and
     * SharedPreferences are not a dependable cross-process channel: each
     * process keeps its own in-memory cache, and the dashboard writes
     * these with an asynchronous `.apply()`. Reading them here would work
     * most of the time (a freshly-spawned game process does load from
     * disk) but only by relying on undocumented flush ordering. The
     * launching Intent is an explicit, synchronous hand-off instead.
     *
     * #185 - minimalOnly (independent of the `enabled` Settings toggle):
     * skips the D-pad/face-button/shoulder-button clusters entirely and
     * only builds the bottom SELECT/START/L3/R3 row. Touch Lightgun hides
     * the full overlay so it doesn't sit on top of tap-to-aim, but on a
     * touch-only device (no physical buttons at all) that would otherwise
     * leave no way to reach Start/Select/L3/R3 - found on real hardware
     * (Samsung, 2026-09-06): "we need bottom, select start r3 l3."
     */
    fun attach(enabled: Boolean, stickMode: Boolean, opacity: Float, minimalOnly: Boolean = false) {
        if (!enabled && !minimalOnly) return

        val layout = SDLActivity.mLayout ?: return
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        val opacityAlpha = (opacity * 255f).toInt().coerceIn(0, 255)
        val padTheme = buildPadTheme(opacityAlpha)

        SDLControllerManager.nativeAddJoystick(
            deviceId,
            "Hypdroid Touch Controls",
            "hypdroid-touch-controls",
            0,
            0,
            LEFT_BUTTON_MASK or RIGHT_BUTTON_MASK,
            6,
            0x003f,
            0,
            false,
            false,
            false,
            false,
        )

        // Shared by the shoulder buttons (skipped when minimalOnly) and the
        // bottom SELECT/START/L3/R3 row (always built), so this has to be
        // computed unconditionally either way.
        val shoulderWidthPx = (0.145f * min(metrics.widthPixels, metrics.heightPixels)).toInt()
        val shoulderHeightPx = (0.078f * min(metrics.widthPixels, metrics.heightPixels)).toInt()

        if (!minimalOnly) {
            // Sized off the shorter screen dimension (== height in landscape
            // gameplay) rather than a fixed dp value, so it's a real thumb-sized
            // target on a tablet instead of a small fraction of one.
            val mainPadSizePx = (0.294f * min(metrics.widthPixels, metrics.heightPixels)).toInt()

            val dpadPrimary = if (stickMode) {
                PrimaryDialConfig.Stick(id = dpadStickId, contentDescription = "Movement")
            } else {
                PrimaryDialConfig.Cross(CrossConfig(id = dpadStickId))
            }
            val dpadPad = RadialGamePad(
                RadialGamePadConfig(
                    sockets = 4,
                    primaryDial = dpadPrimary,
                    secondaryDials = emptyList(),
                    theme = padTheme,
                ),
                8f,
                activity,
            )
            val facePad = RadialGamePad(
                RadialGamePadConfig(
                    sockets = 4,
                    primaryDial = PrimaryDialConfig.PrimaryButtons(
                        // Same 4 geometric slots as before, relabeled per the
                        // owner's on-device correction: old A-slot -> B, old
                        // B-slot -> Y, old Y-slot -> A, X-slot unchanged.
                        dials = listOf(
                            ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_B, label = "B"),
                            ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_Y, label = "Y"),
                            ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_X, label = "X"),
                            ButtonConfig(id = KeyEvent.KEYCODE_BUTTON_A, label = "A"),
                        ),
                    ),
                    secondaryDials = emptyList(),
                    theme = padTheme,
                ),
                8f,
                activity,
            )

            addView(
                layout, dpadPad, mainPadSizePx, mainPadSizePx,
                startRule = RelativeLayout.ALIGN_PARENT_START,
                centerVertical = true,
            )
            addView(
                layout, facePad, mainPadSizePx, mainPadSizePx,
                endRule = RelativeLayout.ALIGN_PARENT_END,
                centerVertical = true,
            )

            val eventScope = CoroutineScope(Dispatchers.Main + Job())
            scope = eventScope
            eventScope.launch { dpadPad.events().collect { handleEvent(it, stickMode) } }
            eventScope.launch { facePad.events().collect { handleEvent(it, stickMode) } }

            // Shoulder buttons - separated entirely from the D-pad/face-button
            // pads and pinned to the top corners, big rectangular targets. #97 -
            // sized off the shorter screen dimension, same basis as mainPadSizePx
            // above, instead of a fixed dp constant that stayed the same physical
            // size regardless of device. Fractions chosen to match the previous
            // 120dp x 64dp constants' actual rendered size on the Samsung Tab
            // S7+ (340dpi, 1752px shorter dimension) - the real device this was
            // already tuned/verified against - so this is a like-for-like swap
            // in sizing basis, not a visual redesign.
            val l1 = plainButton("L1", opacityAlpha, R.drawable.hypdroid_touch_bumper_left_b)
            val l2 = plainButton("L2", opacityAlpha, R.drawable.hypdroid_touch_trigger_left_b)
            val r1 = plainButton("R1", opacityAlpha, R.drawable.hypdroid_touch_bumper_right_b)
            val r2 = plainButton("R2", opacityAlpha, R.drawable.hypdroid_touch_trigger_right_b)

            addView(
                layout, l1, shoulderWidthPx, shoulderHeightPx,
                startRule = RelativeLayout.ALIGN_PARENT_START,
                topRule = RelativeLayout.ALIGN_PARENT_TOP,
            )
            addView(
                layout, l2, shoulderWidthPx, shoulderHeightPx,
                startRule = RelativeLayout.ALIGN_PARENT_START,
                below = l1,
                topMarginPx = (12 * density).toInt(),
            )
            addView(
                layout, r1, shoulderWidthPx, shoulderHeightPx,
                endRule = RelativeLayout.ALIGN_PARENT_END,
                topRule = RelativeLayout.ALIGN_PARENT_TOP,
            )
            addView(
                layout, r2, shoulderWidthPx, shoulderHeightPx,
                endRule = RelativeLayout.ALIGN_PARENT_END,
                below = r1,
                topMarginPx = (12 * density).toInt(),
            )

            bindPlainButton(l1, KeyEvent.KEYCODE_BUTTON_L1)
            bindPlainButton(l2, KeyEvent.KEYCODE_BUTTON_L2)
            bindPlainButton(r1, KeyEvent.KEYCODE_BUTTON_R1)
            bindPlainButton(r2, KeyEvent.KEYCODE_BUTTON_R2)
        }

        // SELECT/START - centered at the bottom, same shape/size as the
        // shoulder buttons, matching PPSSPP's bottom-center placement. L3/R3
        // (thumbstick-click) flank them - the owner's real hypinput_gamepad.ini
        // already binds BUTTON_LEFTSTICK/RIGHTSTICK (KEY_SERVICE/KEY_QUIT on
        // the physical Retroid), which touch otherwise has no way to reach.
        val centerRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val stickButtonSizePx = shoulderHeightPx
        val l3 = plainCircleButton("L3", opacityAlpha, R.drawable.hypdroid_touch_stick_cap_b)
        val select = plainButton("SELECT", opacityAlpha, R.drawable.hypdroid_touch_pill_b)
        val start = plainButton("START", opacityAlpha, R.drawable.hypdroid_touch_pill_b)
        val r3 = plainCircleButton("R3", opacityAlpha, R.drawable.hypdroid_touch_stick_cap_b)
        centerRow.addView(l3, LinearLayout.LayoutParams(stickButtonSizePx, stickButtonSizePx))
        centerRow.addView(
            select,
            LinearLayout.LayoutParams(shoulderWidthPx, shoulderHeightPx).apply {
                marginStart = (16 * density).toInt()
            },
        )
        centerRow.addView(
            start,
            LinearLayout.LayoutParams(shoulderWidthPx, shoulderHeightPx).apply {
                marginStart = (16 * density).toInt()
            },
        )
        centerRow.addView(
            r3,
            LinearLayout.LayoutParams(stickButtonSizePx, stickButtonSizePx).apply {
                marginStart = (16 * density).toInt()
            },
        )
        addView(
            layout, centerRow,
            centerHorizontal = true,
            bottomRule = RelativeLayout.ALIGN_PARENT_BOTTOM,
        )
        bindPlainButton(select, KeyEvent.KEYCODE_BUTTON_SELECT)
        bindPlainButton(start, KeyEvent.KEYCODE_BUTTON_START)
        bindPlainButton(l3, KeyEvent.KEYCODE_BUTTON_THUMBL)
        bindPlainButton(r3, KeyEvent.KEYCODE_BUTTON_THUMBR)
    }

    fun detach() {
        scope?.cancel()
        scope = null

        val layout = SDLActivity.mLayout
        for (view in addedViews) {
            layout?.removeView(view)
        }
        addedViews.clear()

        if (activeDpadDirections.isNotEmpty()) {
            for (key in activeDpadDirections) {
                SDLControllerManager.onNativePadUp(deviceId, key, 0)
            }
            activeDpadDirections = emptySet()
        }

        SDLControllerManager.nativeRemoveJoystick(deviceId)
    }

    // #98/#102 - assets live in src/main/res/drawable, so both flavors get
    // the same art (Handheld's Touch Controls toggle is reachable even
    // though the hardware isn't touch-first, and should look consistent
    // if anyone does turn it on).
    private fun themedDrawable(assetRes: Int?): Drawable? {
        return assetRes?.let { ContextCompat.getDrawable(activity, it) }
    }

    private fun plainCircleButton(label: String, alpha: Int, assetRes: Int? = null): Button {
        return Button(activity).apply {
            text = label
            textSize = 16f
            background = themedDrawable(assetRes)?.apply { mutate().alpha = alpha }
                ?: GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(alpha, 40, 40, 40))
                }
            setTextColor(Color.argb(alpha, 255, 255, 255))
        }
    }

    private fun plainButton(label: String, alpha: Int, assetRes: Int? = null): Button {
        return Button(activity).apply {
            text = label
            background = themedDrawable(assetRes)?.apply { mutate().alpha = alpha }
                ?: GradientDrawable().apply { setColor(Color.argb(alpha, 40, 40, 40)) }
            setTextColor(Color.argb(alpha, 255, 255, 255))
        }
    }

    private fun buildPadTheme(alpha: Int): RadialGamePadTheme {
        // Same base colors as RadialGamePad's own defaults (Constants.kt),
        // just with the alpha channel driven by the owner's opacity slider
        // instead of the library's fixed values. pressedColor stays fully
        // solid always, so touch feedback stays clear even at low resting
        // visibility - now the app's own green accent, matching the #98
        // static-button press tint instead of RadialGamePad's default gray.
        // #114 - normalColor (the D-pad/ABXY buttons' own default-state
        // color, confirmed against RadialGamePadTheme.kt's own doc comment,
        // not the press highlight above) now matches the navy used by the
        // #98/#102 static shoulder-button vector art (hypdroid_touch_*.xml,
        // e.g. #111B2B's mid-layer fill), instead of RadialGamePad's
        // default gray - same visual family as the app's other touch
        // controls, not the unrelated green accent used for focus rings.
        return RadialGamePadTheme(
            normalColor = Color.argb(alpha, 17, 27, 43),
            pressedColor = HypdroidGreenAccent.toArgb(),
            simulatedColor = Color.argb(alpha, 125, 125, 125),
            textColor = Color.argb(alpha, 255, 255, 255),
            backgroundColor = Color.argb((alpha * 50 / 125), 125, 125, 125),
            lightColor = Color.argb((alpha * 30 / 125), 125, 125, 125),
        )
    }

    // #98 - same press-feedback mechanism RadialGamePad itself uses
    // (PrimaryButtonsDial.kt swaps in theme.pressedColor while held) - a
    // plain color tint over the button's own drawable, not a shape change.
    private val pressedTint = PorterDuffColorFilter(HypdroidGreenAccent.toArgb(), PorterDuff.Mode.SRC_ATOP)

    private fun bindPlainButton(button: Button, keycode: Int) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    button.background?.colorFilter = pressedTint
                    handleButtonPress(keycode, pressed = true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.background?.colorFilter = null
                    handleButtonPress(keycode, pressed = false)
                    true
                }
                else -> false
            }
        }
    }

    private fun addView(
        layout: ViewGroup,
        view: View,
        widthPx: Int? = null,
        heightPx: Int? = null,
        startRule: Int? = null,
        endRule: Int? = null,
        topRule: Int? = null,
        bottomRule: Int? = null,
        centerVertical: Boolean = false,
        centerHorizontal: Boolean = false,
        below: View? = null,
        topMarginPx: Int = 0,
    ) {
        val params = RelativeLayout.LayoutParams(
            widthPx ?: RelativeLayout.LayoutParams.WRAP_CONTENT,
            heightPx ?: RelativeLayout.LayoutParams.WRAP_CONTENT,
        )
        startRule?.let { params.addRule(it) }
        endRule?.let { params.addRule(it) }
        topRule?.let { params.addRule(it) }
        bottomRule?.let { params.addRule(it) }
        if (centerVertical) params.addRule(RelativeLayout.CENTER_VERTICAL)
        if (centerHorizontal) params.addRule(RelativeLayout.CENTER_HORIZONTAL)
        params.topMargin = topMarginPx
        below?.let {
            if (it.id == View.NO_ID) it.id = View.generateViewId()
            params.addRule(RelativeLayout.BELOW, it.id)
        }
        layout.addView(view, params)
        addedViews += view
    }

    private fun handleEvent(event: Event, stickMode: Boolean) {
        when (event) {
            is Event.Button -> handleButtonPress(event.id, event.action == KeyEvent.ACTION_DOWN)
            is Event.Direction -> handleDirection(event, stickMode)
            is Event.Gesture -> Unit
        }
    }

    private fun handleButtonPress(keycode: Int, pressed: Boolean) {
        when (keycode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                SDLControllerManager.onNativeJoy(deviceId, axisTriggerLeft, if (pressed) 1f else 0f)
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                SDLControllerManager.onNativeJoy(deviceId, axisTriggerRight, if (pressed) 1f else 0f)
            }
            else -> {
                if (pressed) {
                    SDLControllerManager.onNativePadDown(deviceId, keycode, 0)
                } else {
                    SDLControllerManager.onNativePadUp(deviceId, keycode, 0)
                }
            }
        }
    }

    private fun handleDirection(event: Event.Direction, stickMode: Boolean) {
        if (event.id != dpadStickId) return

        if (stickMode) {
            SDLControllerManager.onNativeJoy(deviceId, axisLeftX, event.xAxis)
            SDLControllerManager.onNativeJoy(deviceId, axisLeftY, event.yAxis)
            return
        }

        // CrossDial already reports quantized -1/0/1 values per axis (see
        // CrossDial.kt's State enum) - this just turns that into discrete
        // dpad button down/up transitions, the same shape a real physical
        // d-pad's key events already take.
        val newDirections = mutableSetOf<Int>()
        if (event.xAxis > 0.5f) newDirections += KeyEvent.KEYCODE_DPAD_RIGHT
        if (event.xAxis < -0.5f) newDirections += KeyEvent.KEYCODE_DPAD_LEFT
        if (event.yAxis < -0.5f) newDirections += KeyEvent.KEYCODE_DPAD_UP
        if (event.yAxis > 0.5f) newDirections += KeyEvent.KEYCODE_DPAD_DOWN

        for (key in newDirections - activeDpadDirections) {
            SDLControllerManager.onNativePadDown(deviceId, key, 0)
        }
        for (key in activeDpadDirections - newDirections) {
            SDLControllerManager.onNativePadUp(deviceId, key, 0)
        }
        activeDpadDirections = newDirections
    }

    companion object {
        // Bit values sourced directly from SDLControllerManager.getButtonMask()'s
        // own keys[]/masks[] table (real Android gamepad discovery), not
        // guessed - only the buttons this overlay actually sends.
        private const val LEFT_BUTTON_MASK =
            (1 shl 4) or // SELECT/BACK
                (1 shl 7) or // THUMBL/LEFTSTICK (L3)
                (1 shl 9) or // L1/LEFTSHOULDER
                (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14) // DPAD
        private const val RIGHT_BUTTON_MASK =
            (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3) or // A/B/X/Y
                (1 shl 6) or // START
                (1 shl 8) or // THUMBR/RIGHTSTICK (R3)
                (1 shl 10) // R1/RIGHTSHOULDER
    }
}
