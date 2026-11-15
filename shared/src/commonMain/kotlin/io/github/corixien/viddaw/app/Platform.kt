package io.github.corixien.viddaw.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.github.corixien.viddaw.model.Overlay
import io.github.corixien.viddaw.model.VideoFx

class PickedMedia(val uri: String, val name: String)

enum class ExportFormat(val label: String, val extension: String, val video: Boolean = false) {
    Wav("WAV", "wav"), Flac("FLAC", "flac"), M4a("M4A (AAC)", "m4a"), Mp4("MP4 video", "mp4", video = true)
}

/** A video clip resolved to seconds, in timeline order, for preview and export. */
class VideoSegment(
    val uri: String, val startSec: Double, val sourceOffsetSec: Double, val durationSec: Double,
    val fx: VideoFx = VideoFx(), val clipId: Long = 0,
)

/** A text or shape resolved to seconds. */
class OverlaySegment(val overlay: Overlay, val startSec: Double, val durationSec: Double, val clipId: Long = 0)

/** Everything the shared code needs from the host OS. One implementation per platform. */
interface Platform {
    /** Root folder for projects and settings. */
    val dataDir: String
    val systemLanguage: String

    fun readText(path: String): String?
    fun writeText(path: String, text: String)
    fun listDirs(path: String): List<String>
    fun delete(path: String)
    fun mkdirs(path: String)
    fun copy(from: String, to: String)
    fun nowMillis(): Long

    suspend fun requestMicPermission(): Boolean
    suspend fun pickAudio(): PickedMedia?
    suspend fun pickVideo(): PickedMedia?

    /** Decodes the audio of any media file into a float WAV at the engine rate. Returns frames, 0 on failure. */
    suspend fun importAudio(uri: String, destWav: String, onProgress: (Float) -> Unit): Long

    /** Duration of a video in milliseconds, or 0 if it cannot be read. */
    suspend fun videoDurationMs(uri: String): Long

    /** Encodes a bounced WAV to [format] and saves it for the user. Returns where it went, null on failure. */
    suspend fun exportAudio(wavPath: String, format: ExportFormat, name: String): String?

    /** Width over height of a video as it is shown, 0 if it cannot be read. */
    suspend fun videoAspect(uri: String): Float

    /** One frame of a video scaled to [height] pixels, or null if it cannot be read. */
    suspend fun videoFrame(uri: String, timeMs: Long, height: Int): ImageBitmap?

    /** Renders [segments] and [overlays] with [wavPath] as soundtrack into an MP4. Returns where it went, null on failure. */
    suspend fun exportVideo(segments: List<VideoSegment>, overlays: List<OverlaySegment>, wavPath: String, totalSec: Double, name: String, onProgress: (Float) -> Unit): String?

    /** Nearby Bluetooth MIDI devices as (address in uri, name). Empty if none or not permitted. */
    suspend fun scanBluetoothMidi(): List<PickedMedia>
    suspend fun connectBluetoothMidi(address: String): Boolean

    /** External MIDI keyboards: called with (pitch, velocity, noteOn) on the main thread. */
    var onMidiNote: ((Int, Int, Boolean) -> Unit)?

    /**
     * Shows the frame of [segments] at [positionSec] with each clip's colour grade and blur, following
     * playback when [playing]. Placement, transitions and overlays are drawn by the caller.
     */
    @Composable
    fun VideoPreview(segments: List<VideoSegment>, positionSec: () -> Double, playing: Boolean, modifier: Modifier)
}
