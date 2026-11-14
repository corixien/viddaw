package io.github.corixien.viddaw

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.github.corixien.viddaw.app.VideoSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Video on Android through Media3. The engine stays the clock and plays all sound;
 * the player here is muted and chases the engine position.
 */
@OptIn(UnstableApi::class)
class VideoSupport(private val context: Context) {

    @Composable
    fun Preview(segments: List<VideoSegment>, positionSec: () -> Double, playing: Boolean, modifier: Modifier) {
        val player = remember {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            }
        }
        var aspect by remember { mutableFloatStateOf(16f / 9f) }
        var visible by remember { mutableStateOf(false) }
        val currentSegments by rememberUpdatedState(segments)
        val isPlaying by rememberUpdatedState(playing)

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
            player.addListener(listener)
            onDispose { player.release() }
        }

        LaunchedEffect(player) {
            var loaded: String? = null
            while (true) {
                val t = positionSec()
                val seg = currentSegments.firstOrNull { t >= it.startSec && t < it.startSec + it.durationSec }
                visible = seg != null
                if (seg == null) {
                    if (player.playWhenReady) player.pause()
                } else {
                    if (loaded != seg.uri) {
                        loaded = seg.uri
                        player.setMediaItem(MediaItem.fromUri(seg.uri))
                        player.prepare()
                    }
                    val want = ((t - seg.startSec + seg.sourceOffsetSec) * 1000).toLong()
                    val drift = abs(player.currentPosition - want)
                    val ready = player.playbackState == Player.STATE_READY
                    if (isPlaying) {
                        if (!player.playWhenReady) {
                            player.seekTo(want)
                            player.play()
                        } else if (ready && drift > 150) {
                            player.seekTo(want)
                        }
                    } else {
                        if (player.playWhenReady) player.pause()
                        if (drift > 25 && (ready || player.playbackState == Player.STATE_ENDED)) player.seekTo(want)
                    }
                }
                delay(if (isPlaying) 120 else 40)
            }
        }

        Box(modifier, contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { SurfaceView(it).also(player::setVideoSurfaceView) },
                modifier = Modifier.aspectRatio(aspect).alpha(if (visible) 1f else 0f),
            )
        }
    }

    /** Output size of the first clip with the long side capped at 1920, rounded to even numbers. */
    private fun outputSize(uri: String): Pair<Int, Int> = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, Uri.parse(uri))
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val scale = minOf(1.0, 1920.0 / maxOf(w, h))
            ((w * scale).toInt() / 2 * 2) to ((h * scale).toInt() / 2 * 2)
        }
    }.getOrDefault(1920 to 1080)

    /**
     * Renders the video clips, with black where the timeline has none, over the bounced soundtrack.
     */
    suspend fun export(segments: List<VideoSegment>, wavPath: String, totalSec: Double, out: File, onProgress: (Float) -> Unit): Boolean {
        if (segments.isEmpty()) return false
        val (width, height) = withContext(Dispatchers.IO) { outputSize(segments.first().uri) }
        return withContext(Dispatchers.Main) {
            val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).apply {
                var cursor = 0.0
                fun gapTo(sec: Double) {
                    if (sec - cursor > 0.02) addGap(((sec - cursor) * 1_000_000).toLong())
                }
                for (s in segments) {
                    gapTo(s.startSec)
                    val clip = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((s.sourceOffsetSec * 1000).toLong())
                        .setEndPositionMs(((s.sourceOffsetSec + s.durationSec) * 1000).toLong())
                        .build()
                    addItem(
                        EditedMediaItem.Builder(MediaItem.Builder().setUri(s.uri).setClippingConfiguration(clip).build())
                            .setRemoveAudio(true).build(),
                    )
                    cursor = s.startSec + s.durationSec
                }
                gapTo(totalSec)
            }.build()
            val audio = EditedMediaItemSequence.withAudioFrom(
                listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(wavPath)))).build()),
            )
            val composition = Composition.Builder(video, audio)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()

            val done = CompletableDeferred<Boolean>()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        android.util.Log.e("Viddaw", "video export failed", exportException)
                        done.complete(false)
                    }
                })
                .build()
            out.delete()
            transformer.start(composition, out.path)
            val holder = ProgressHolder()
            while (!done.isCompleted) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(150)
            }
            done.await()
        }
    }
}
