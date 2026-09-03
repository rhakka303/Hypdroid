package org.libsdl.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hypdroid.mpvbridge.MpvPlayerView

/**
 * #167 - libmpv build-spike test screen, debug-only, not part of the real
 * Attract Mode UI. Goal is only "does a real .m2v play with .ogg audio
 * inside this app," nothing else - see the issue for full scope.
 *
 * Hardcoded to a real test file pushed to /sdcard/Download/ for this spike
 * (hayate_se's actual attract footage, the same file already proven working
 * via desktop mpv in smoke/video-snaps-ideas.md) - not wired to any real
 * game-scanning/offset-lookup logic, that's #168's job once this proves out.
 */
@Composable
fun MpvTestScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("MPV Test (debug)", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.height(16.dp))
        MpvPlayerView(
            videoPath = "/sdcard/Download/mpv_spike_test.m2v",
            audioPath = "/sdcard/Download/mpv_spike_test.ogg",
            modifier = Modifier.weight(1f),
        )
    }
}
