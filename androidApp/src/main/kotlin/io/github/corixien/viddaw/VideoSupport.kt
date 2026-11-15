package io.github.corixien.viddaw

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.TextureView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.github.corixien.viddaw.app.OverlaySegment
import io.github.corixien.viddaw.app.VideoSegment
import io.github.corixien.viddaw.model.OVERLAY_COLORS
import io.github.corixien.viddaw.model.Overlay
import io.github.corixien.viddaw.model.VideoFx
import io.github.corixien.viddaw.model.colorMatrix
import io.github.corixien.viddaw.model.hasColor
import io.github.corixien.viddaw.model.transitionAt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
/** Blur at full strength as a share of the frame height (Gaussian sigma). */
private const val BLUR_SHARE = 0.03f

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
        val view = remember { arrayOfNulls<TextureView>(1) }
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
            var graded: VideoFx? = null
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
                    if (graded != seg.fx) {
                        graded = seg.fx
                        view[0]?.let { grade(it, seg.fx) }
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
                factory = { TextureView(it).also { v -> view[0] = v; player.setVideoTextureView(v) } },
                modifier = Modifier.aspectRatio(aspect).alpha(if (visible) 1f else 0f),
            )
        }
    }

    /** Colour grade and blur of the preview. Placement and fades are applied by the shared UI. */
    private fun grade(view: TextureView, fx: VideoFx) {
        val m = fx.copy(opacity = 1f).colorMatrix()
        view.setLayerPaint(if (!fx.copy(opacity = 1f).hasColor) null else Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                m[0], m[1], m[2], 0f, m[3] * 255f,
                m[4], m[5], m[6], 0f, m[7] * 255f,
                m[8], m[9], m[10], 0f, m[11] * 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
        })
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = fx.blur * BLUR_SHARE * view.height / 0.577f
            view.setRenderEffect(if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }

    private val frameLock = Mutex()
    private var frameSource: Pair<String, MediaMetadataRetriever>? = null

    /** One frame for the timeline. The retriever of the last video stays open for the next call. */
    suspend fun frame(uri: String, timeMs: Long, height: Int): ImageBitmap? = frameLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val r = frameSource?.takeIf { it.first == uri }?.second ?: MediaMetadataRetriever().also {
                    frameSource?.second?.release()
                    frameSource = null
                    it.setDataSource(context, Uri.parse(uri))
                    frameSource = uri to it
                }
                r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, height * 3, height)?.asImageBitmap()
            }.getOrNull()
        }
    }

    /** Seconds since the first frame an effect chain saw, so effects need not know how the pipeline numbers time. */
    private class Clock {
        private var first = Long.MIN_VALUE
        fun seconds(presentationTimeUs: Long): Double {
            if (presentationTimeUs < 0) return 0.0
            if (first == Long.MIN_VALUE) first = presentationTimeUs
            return (presentationTimeUs - first) / 1_000_000.0
        }
    }

    /** The clip's placement, transitions, blur and colour as export effects, in the order the preview applies them. */
    private fun clipEffects(s: VideoSegment, width: Int, height: Int): List<Effect> {
        val fx = s.fx
        val effects = ArrayList<Effect>()
        effects += Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        val clock = Clock()
        val animated = fx.transIn != 0 || fx.transOut != 0
        if (animated || fx.scale != 1f || fx.x != 0f || fx.y != 0f || fx.rotation != 0f || fx.flip) {
            val aspect = width.toFloat() / height
            effects += MatrixTransformation { pts ->
                val tr = fx.transitionAt(clock.seconds(pts), s.durationSec)
                val k = fx.scale * tr.scale
                // Frame coordinates run -1..1 with y up; rotate in square pixels, then move.
                Matrix().apply {
                    postScale(aspect, 1f)
                    postScale(if (fx.flip) -k else k, k)
                    postRotate(-fx.rotation)
                    postScale(1f / aspect, 1f)
                    postTranslate(2f * (fx.x + tr.dx), -2f * (fx.y + tr.dy))
                }
            }
        }
        if (fx.blur > 0f) effects += GaussianBlur(fx.blur * BLUR_SHARE * height)
        if (animated || fx.hasColor) {
            effects += RgbMatrix { pts, _ ->
                val m = fx.colorMatrix(fx.transitionAt(clock.seconds(pts), s.durationSec).fade)
                floatArrayOf(
                    m[0], m[4], m[8], 0f,
                    m[1], m[5], m[9], 0f,
                    m[2], m[6], m[10], 0f,
                    m[3], m[7], m[11], 1f,
                )
            }
        }
        return effects
    }

    /** A frame-sized transparent bitmap with one overlay on it. Mirrors drawOverlay in the shared UI. */
    private fun overlayBitmap(o: Overlay, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f + o.x * width
        val cy = height / 2f + o.y * height
        val argb = OVERLAY_COLORS[o.color.coerceIn(0, OVERLAY_COLORS.lastIndex)].toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            alpha = (o.opacity.coerceIn(0f, 1f) * 255).toInt()
        }
        canvas.rotate(o.rotation, cx, cy)
        val hh = o.size * height / 2
        when (o.kind) {
            0 -> {
                val px = o.size * height * 0.8f
                paint.textSize = px
                paint.typeface = context.resources.getFont(R.font.inter_semibold)
                paint.letterSpacing = -0.1f / 16f
                val textWidth = paint.measureText(o.text)
                val fm = paint.fontMetrics
                if (o.box) {
                    val pad = px * 0.25f
                    val halfW = textWidth / 2 + pad
                    val halfH = (fm.descent - fm.ascent) / 2 + pad * 0.4f
                    val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; alpha = (0.55f * o.opacity * 255).toInt() }
                    canvas.drawRoundRect(RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH), px * 0.2f, px * 0.2f, plate)
                }
                canvas.drawText(o.text, cx - textWidth / 2, cy - (fm.ascent + fm.descent) / 2, paint)
            }
            1 -> canvas.drawRect(cx - hh * o.aspect, cy - hh, cx + hh * o.aspect, cy + hh, paint)
            else -> canvas.drawOval(RectF(cx - hh * o.aspect, cy - hh, cx + hh * o.aspect, cy + hh), paint)
        }
        return bitmap
    }

    /** Shows its bitmap only while the overlay clip lasts. */
    private class TimedOverlay(private val bitmap: Bitmap, private val startSec: Double, private val endSec: Double, private val clock: Clock) : BitmapOverlay() {
        private val shown = StaticOverlaySettings.Builder().build()
        private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val t = clock.seconds(presentationTimeUs)
            return if (t >= startSec && t < endSec) shown else hidden
        }
    }

    /** Output size of the first clip with the long side capped at 1920, rounded to even numbers. */
    fun outputSize(uri: String): Pair<Int, Int> = runCatching {
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
    suspend fun export(segments: List<VideoSegment>, overlays: List<OverlaySegment>, wavPath: String, totalSec: Double, out: File, onProgress: (Float) -> Unit): Boolean {
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
                            .setRemoveAudio(true).setEffects(Effects(emptyList(), clipEffects(s, width, height))).build(),
                    )
                    cursor = s.startSec + s.durationSec
                }
                gapTo(totalSec)
            }.build()
            val audio = EditedMediaItemSequence.withAudioFrom(
                listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(wavPath)))).build()),
            )
            val frameEffects = ArrayList<Effect>()
            frameEffects += Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
            if (overlays.isNotEmpty()) {
                val clock = Clock()
                frameEffects += OverlayEffect(overlays.map { TimedOverlay(overlayBitmap(it.overlay, width, height), it.startSec, it.startSec + it.durationSec, clock) })
            }
            val composition = Composition.Builder(video, audio).setEffects(Effects(emptyList(), frameEffects)).build()

            val done = CompletableDeferred<Boolean>()
            // Grade in the video's own gamma, as the preview's colour filter does.
            val processor = DefaultVideoFrameProcessor.Factory.Builder()
                .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL).build()
            val transformer = Transformer.Builder(context)
                .setVideoFrameProcessorFactory(processor)
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
