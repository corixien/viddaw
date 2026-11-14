package io.github.corixien.viddaw

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.corixien.viddaw.app.VideoSegment
import java.io.File

/** Video preview and export. Filled in with Media3 in the video milestone. */
class VideoSupport(private val context: Context) {
    @Composable
    fun Preview(segments: List<VideoSegment>, positionSec: () -> Double, playing: Boolean, modifier: Modifier) {
    }

    suspend fun export(segments: List<VideoSegment>, wavPath: String, totalSec: Double, out: File, onProgress: (Float) -> Unit): Boolean = false
}
