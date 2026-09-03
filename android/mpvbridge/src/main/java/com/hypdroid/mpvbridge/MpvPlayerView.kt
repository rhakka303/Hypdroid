package com.hypdroid.mpvbridge

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.jdtech.mpv.MPVLib
import java.io.File

/**
 * #167/#168 - libmpv-backed video surface.
 *
 * Real architectural bug found and fixed (2026-09-03): the first version
 * of this composable created a brand new SurfaceView + MPVLib instance
 * every time it entered composition, and destroyed it on every exit -
 * matching a naive "one player per Compose call site" model. Wired up
 * one-per-carousel-page (see MainActivity.kt's original #168 attempt),
 * that meant a fresh MPVLib.create()/destroy() cycle on *every single
 * swipe* between games. `MPVLib` is a real native global singleton (mirrors
 * mpv-android's own architecture) - rapid create/destroy churn against it,
 * especially destroying while the previous instance's render thread might
 * still be active, produced real crashes on the Retroid Pocket 5 (`libmpv
 * is not initialized`, then the process exiting).
 *
 * Fixed by making this genuinely a *singleton-shaped* composable: the
 * `AndroidView` factory (and MPVLib.create()/init()) runs exactly once for
 * as long as this composable stays mounted. [videoPath]/[audioPath]/
 * [startSeconds]/[endSeconds] changing (e.g. the carousel's focused game
 * changing) re-issues `loadfile` on the *same* instance via a
 * [LaunchedEffect], instead of tearing anything down. Callers should mount
 * exactly one of these for as long as video might be shown (see
 * MainActivity.kt's hoisted-outside-the-pager placement for the real
 * carousel usage) - not one per possible video, which is what caused the
 * original bug.
 *
 * [audioPath] mirrors the desktop `mpv --audio-file=` flag already proven
 * working against a real `.m2v`/`.ogg` pair (see smoke/video-snaps-ideas.md) -
 * via the `audio-add` runtime command here, not the CLI flag's literal name
 * (that's not a real settable option on the embedded API, see below).
 */
@Composable
fun MpvPlayerView(
    // Null is a real, expected state here (not an error) - "this instance
    // exists but nothing is focused/has attract data right now."
    videoPath: String?,
    audioPath: String? = null,
    // #168 - the attract-loop range. Null means "play the whole file once,"
    // used by #167's original debug test screen; a real Attract Mode clip
    // always passes both.
    startSeconds: Double? = null,
    endSeconds: Double? = null,
    // "loop 5 times" (#168's settled spec) = 1 natural play + 4 repeats -
    // mpv's own loop-file counts *additional* repeats, not total plays.
    loopCount: Int = 4,
    modifier: Modifier = Modifier,
) {
    var surfaceReady by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).also { surfaceView ->
                initMpv(context)
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        MPVLib.attachSurface(holder.surface)
                        MPVLib.setOptionString("force-window", "yes")
                        surfaceReady = true
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        surfaceReady = false
                        MPVLib.setOptionString("force-window", "no")
                        MPVLib.detachSurface()
                    }
                })
            }
        },
    )

    // Re-issues loadfile on the one persistent instance whenever the clip
    // identity changes (including the very first time, once the surface
    // is actually ready to receive frames) - never destroy+recreate.
    LaunchedEffect(surfaceReady, videoPath, audioPath, startSeconds, endSeconds) {
        if (!surfaceReady) return@LaunchedEffect
        if (videoPath == null) {
            MPVLib.command(arrayOf("stop"))
            return@LaunchedEffect
        }
        // Real bug found and fixed (2026-09-03): loop-file combined with
        // per-load start/end, reloaded onto the same persistent instance
        // a second time (i.e. re-focusing a game after navigating away and
        // back), produced a near-instant EOF (~230ms in, nowhere near the
        // real ~21s range) followed by `event: shutdown` - mpv's actual
        // playback core terminating, not just pausing, which is why
        // nothing could restart afterward (confirmed: a full remount via
        // Settings worked fine every time, only same-instance reloads
        // broke - isolates this to loop-file's cross-reload behavior
        // specifically). Dropped loop-file entirely for now - start/end
        // alone plays the range once and keep-open=yes (set in initMpv)
        // freezes on the last frame at EOF, which is reliable across
        // repeated reloads. The "loop 5 times" part of #168's spec is a
        // separate follow-up (event-driven re-seek-to-start instead of
        // relying on mpv's own loop-file option) once this core path is
        // proven solid.
        val loadOptions = if (startSeconds != null && endSeconds != null) {
            "start=$startSeconds,end=$endSeconds"
        } else {
            null
        }
        if (loadOptions != null) {
            // Real bug found and fixed (2026-09-03): loadfile's real
            // signature is `loadfile <url> [<flags> [<index> [<options>]]]`
            // - the options string needs an index argument before it
            // ("-1" = not applicable, matches "replace"), or mpv tries to
            // parse the options string itself as that integer and errors:
            // "The loadfile option must be an integer".
            MPVLib.command(arrayOf("loadfile", videoPath, "replace", "-1", loadOptions))
        } else {
            MPVLib.command(arrayOf("loadfile", videoPath))
        }
        // Real bug found and fixed (2026-09-03): setting "audio-file" as
        // an *option* did nothing - no error, no log line, audio just
        // never loaded. `--audio-file` is a CLI-flag convenience, not a
        // real settable option/property. `audio-add` (a real runtime
        // command, what mpv's own external-audio-track feature actually
        // uses) is the correct mechanism.
        //
        // Second real bug found and fixed (2026-09-03): issuing audio-add
        // immediately after loadfile in the same coroutine tick raced with
        // loadfile actually completing - audio silently never attached
        // (`audio=eof` in mpv's own log, zero trace of the .ogg being
        // touched at all), even though the exact same file/path is
        // confirmed present and readable on-device. A short delay before
        // audio-add - cheap, since we're already in a coroutine - gives
        // loadfile time to actually finish opening the file first.
        if (audioPath != null) {
            kotlinx.coroutines.delay(300)
            MPVLib.command(arrayOf("audio-add", audioPath, "select"))
        }
        // Real bug found and fixed (2026-09-03): navigating away from a
        // focused game (which issues "stop" above) and back to it left
        // the newly-loaded file paused instead of playing - "stop" and/or
        // keep-open's own pause-on-EOF from a prior loop leaves mpv's
        // `pause` property stuck true, and loadfile resets playback state
        // to match it rather than clearing it. Must come *after* loadfile
        // (and audio-add) - un-pausing before loading gets superseded by
        // the newly-loaded file's own state, which was the first, wrong
        // attempt at this fix.
        MPVLib.setPropertyBoolean("pause", false)
    }

    // Real bug found and fixed (2026-09-03): launching a game starts a
    // separate Activity (HypseusActivity), which only backgrounds
    // MainActivity (onPause/onStop) rather than tearing down its Compose
    // composition - so the attract clip kept playing invisibly underneath
    // real gameplay. Pausing (not destroying) on ON_PAUSE avoids the same
    // destroy-while-rendering race described above, while still solving
    // the actual complaint.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                MPVLib.setPropertyBoolean("pause", true)
            }
            // Real bug found and fixed (2026-09-03): returning from an
            // actual game (HypseusActivity closing, MainActivity resuming)
            // left audio audibly playing over a black screen instead of a
            // clean stopped state - pausing alone on ON_PAUSE wasn't
            // enough to guarantee a fully quiet, deterministic state by
            // the time we come back. An explicit stop on ON_RESUME
            // guarantees a clean slate every time, matching the same
          // "always needs a fresh tap" model already chosen for swiping
            // between games.
            if (event == Lifecycle.Event.ON_RESUME) {
                MPVLib.command(arrayOf("stop"))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MPVLib.destroy()
        }
    }
}

private fun initMpv(context: Context) {
    val configDir = File(context.filesDir, "mpv_config").apply { mkdirs() }
    val cacheDir = File(context.cacheDir, "mpv_cache").apply { mkdirs() }

    MPVLib.create(context.applicationContext)
    MPVLib.setOptionString("config-dir", configDir.path)
    MPVLib.setOptionString("cache-dir", cacheDir.path)
    // Real bug found and fixed (2026-09-03): the AAR's default `vo`
    // (mediacodec_embed) is a hardware-decode-passthrough output - it
    // needs frames that came out of a hardware decoder, and errors with
    // "Cannot convert decoder/filter output to any format supported by
    // the output" against ours. We deliberately want software decode for
    // raw .m2v (same reasoning hypseus's own libmpeg2 vs Android hwdec
    // choice already documented in ENVIRONMENT.md - MPEG-2 hw decode
    // support is inconsistent across devices). `gpu` is the generic
    // GLES/EGL output that accepts software-decoded YUV frames directly.
    MPVLib.setOptionString("vo", "gpu")
    MPVLib.setOptionString("hwdec", "no")
    MPVLib.init()
    MPVLib.setOptionString("force-window", "no")
    // Real bug found and fixed (2026-09-03), likely the actual root cause
    // of the whole "doesn't restart after leaving and coming back" saga:
    // this was "once" (copied from mpv-android's own single-shot-player
    // reference pattern - load one file, play it, quit when done). "once"
    // means mpv's core actually terminates once the first file's playback
    // ends, rather than staying alive idle for more loadfile calls - fine
    // for a normal video player, fundamentally wrong for this persistent,
    // reused-many-times-over-the-carousel's-lifetime instance. "yes"
    // keeps the core alive indefinitely between loads, which is what a
    // singleton player that gets reused for every focused game needs.
    MPVLib.setOptionString("idle", "yes")
    // #168 - once loop-file's repeats are exhausted and the (start/end-
    // trimmed) file reaches EOF, pause there instead of closing - this is
    // what actually produces "freeze on the last frame" after the loop.
    MPVLib.setOptionString("keep-open", "yes")
}
