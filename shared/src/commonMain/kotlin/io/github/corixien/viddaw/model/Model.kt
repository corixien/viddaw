package io.github.corixien.viddaw.model

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToLong

/** Timeline positions are in ticks so they follow the tempo; audio lengths are in frames. */
const val PPQ = 960L
const val SAMPLE_RATE = 48000
const val PAD_COUNT = 16
const val FIRST_PAD_NOTE = 36
const val MIN_DB = -60f

enum class TrackType { Audio, Sampler, Drums, Video }

val TrackType.isMidi get() = this == TrackType.Sampler || this == TrackType.Drums

@Serializable
data class Note(val start: Long, val length: Long, val pitch: Int, val velocity: Int = 100)

/**
 * One clip kind per track type:
 * - audio: [file] is a WAV inside the project, [offset]/[frames] select the part that plays
 * - video: [video] is the source URI, trimmed by the same fields; its sound is a separate audio clip
 *   in the same [group] (older projects kept it in [file])
 * - overlay: [overlay] is a text or shape drawn over the picture for [length] ticks, on a video track
 * - MIDI: [length] in ticks and [notes] relative to the clip start; notes outside 0..length are kept but silent
 *
 * Clips that share a non-zero [group] are edited together. [src], [speed] and [pitch] describe how
 * [file] was rendered from the original recording, so a new stretch starts from the original again.
 */
@Serializable
data class Clip(
    val id: Long,
    val start: Long,
    val name: String = "",
    val file: String? = null,
    val offset: Long = 0,
    val frames: Long = 0,
    val fileFrames: Long = 0,
    val gainDb: Float = 0f,
    val video: String? = null,
    val length: Long = 0,
    val notes: List<Note> = emptyList(),
    val fadeIn: Long = 0,
    val fadeOut: Long = 0,
    val group: Long = 0,
    val src: String? = null,
    val speed: Float = 1f,
    val pitch: Float = 0f,
    val fx: VideoFx = VideoFx(),
    val overlay: Overlay? = null,
) {
    /** Length counted in ticks rather than frames: MIDI and overlay clips. */
    val tickBased get() = file == null && video == null
    val isMidi get() = tickBased && overlay == null
    val isAudio get() = file != null && video == null
}

@Serializable
data class EffectInstance(val id: Long, val type: String, val enabled: Boolean = true, val params: List<Float>)

@Serializable
data class SamplerConfig(
    val file: String? = null,
    val name: String = "",
    val root: Int = 60,
    val tune: Float = 0f,
    val attackMs: Float = 2f,
    val releaseMs: Float = 200f,
    val gainDb: Float = 0f,
)

@Serializable
data class DrumPad(val file: String? = null, val name: String = "", val gainDb: Float = 0f, val pan: Float = 0f, val tune: Float = 0f)

@Serializable
data class Track(
    val id: Long,
    val type: TrackType,
    val name: String,
    val color: Int,
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val clips: List<Clip> = emptyList(),
    val effects: List<EffectInstance> = emptyList(),
    val sampler: SamplerConfig = SamplerConfig(),
    val pads: List<DrumPad> = List(PAD_COUNT) { DrumPad() },
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val tracks: List<Track> = emptyList(),
    val loopStart: Long = 0,
    val loopEnd: Long = PPQ * 16,
    val loopEnabled: Boolean = false,
    val metronome: Boolean = false,
    val masterDb: Float = 0f,
    val nextId: Long = 1,
    val modifiedAt: Long = 0,
    val version: Int = 1,
)

val BUILTIN_PAD_NAMES = listOf(
    "Kick", "Snare", "Closed Hat", "Open Hat", "Clap", "Rim", "Low Tom", "Mid Tom",
    "High Tom", "Crash", "Ride", "Cowbell", "Shaker", "Conga", "Sub Kick", "Snap",
)

fun ticksToFrames(ticks: Long, bpm: Double): Long = (ticks * 60.0 * SAMPLE_RATE / (bpm * PPQ)).roundToLong()
fun framesToTicks(frames: Long, bpm: Double): Long = (frames * bpm * PPQ / (60.0 * SAMPLE_RATE)).roundToLong()
fun dbToLinear(db: Float): Float = if (db <= MIN_DB) 0f else 10.0.pow(db / 20.0).toFloat()

fun Clip.lengthTicks(bpm: Double): Long = if (tickBased) length else framesToTicks(frames, bpm)
fun Clip.endTick(bpm: Double): Long = start + lengthTicks(bpm)

fun Project.clips(ids: Set<Long>): List<Clip> = tracks.flatMap { t -> t.clips.filter { it.id in ids } }

val Project.ticksPerBar get() = PPQ * beatsPerBar
fun Project.endTick(): Long = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endTick(bpm) } ?: 0 } ?: 0
fun Project.track(id: Long): Track? = tracks.firstOrNull { it.id == id }
fun Project.trackOfClip(clipId: Long): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }
fun Project.clip(clipId: Long): Clip? = tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == clipId } }
