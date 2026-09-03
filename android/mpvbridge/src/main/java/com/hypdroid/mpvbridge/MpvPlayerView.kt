package com.hypdroid.mpvbridge

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import java.io.File

/**
 * #167 - libmpv build-spike. Deliberately minimal: prove a video file
 * plays with audio inside a Compose-hosted SurfaceView, using the real
 * init/playback sequence from mpv-android's own BaseMPVView.kt (the
 * reference implementation this AAR's MPVLib API is modeled on) - not
 * guessed from the API surface alone.
 *
 * [audioPath] mirrors the desktop `mpv --audio-file=` flag already proven
 * working against a real `.m2v`/`.ogg` pair (see smoke/video-snaps-ideas.md) -
 * via the `audio-add` runtime command here, not the CLI flag's literal name
 * (that's not a real settable option on the embedded API, see below).
 */
@Composable
fun MpvPlayerView(
    videoPath: String,
    audioPath: String? = null,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).also { surfaceView ->
                initMpv(context)
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        MPVLib.attachSurface(holder.surface)
                        MPVLib.setOptionString("force-window", "yes")
                        MPVLib.command(arrayOf("loadfile", videoPath))
                        // Real bug found and fixed (2026-09-03): setting
                        // "audio-file" as an *option* did nothing - no
                        // error, no log line, audio just never loaded.
                        // `--audio-file` is a CLI-flag convenience, not a
                        // real settable option/property. `audio-add` (a
                        // real runtime command, what mpv's own external-
                        // audio-track feature actually uses) is the
                        // correct mechanism - issued right after loadfile
                        // so it attaches to the file just queued.
                        if (audioPath != null) {
                            MPVLib.command(arrayOf("audio-add", audioPath, "select"))
                        }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        MPVLib.setOptionString("force-window", "no")
                        MPVLib.detachSurface()
                    }
                })
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose { MPVLib.destroy() }
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
    MPVLib.setOptionString("idle", "once")
}
