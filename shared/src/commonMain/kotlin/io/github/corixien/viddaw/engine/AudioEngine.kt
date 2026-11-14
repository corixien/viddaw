package io.github.corixien.viddaw.engine

import io.github.corixien.viddaw.model.FIRST_PAD_NOTE
import io.github.corixien.viddaw.model.Project
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.dbToLinear
import io.github.corixien.viddaw.model.ticksToFrames

/** Frames summarised by one waveform peak value. */
const val PEAK_FRAMES = 512

class Recording(val frames: Long, val startFrame: Long)

/** The native mixing engine as the app sees it. Implemented per platform over the C++ core. */
interface AudioEngine {
    fun start(): Boolean
    fun stop()
    fun sync(snapshot: String)
    fun play()
    fun pause()
    fun seek(frame: Long)
    fun position(): Long
    fun isPlaying(): Boolean
    fun noteOn(track: Long, pitch: Int, velocity: Int)
    fun noteOff(track: Long, pitch: Int)

    /** Fills master L, master R, then one peak per track in project order. Returns values written. */
    fun meters(out: FloatArray): Int
    fun startRecording(path: String): Boolean
    fun stopRecording(): Recording
    fun recordingPeaks(from: Int): FloatArray
    fun latencyFrames(): Int
    fun peaks(path: String): FloatArray

    /** Blocking offline bounce; poll [renderProgress] from another thread. */
    fun renderToFile(path: String, startFrame: Long, endFrame: Long, bits: Int): Boolean
    fun renderProgress(): Float
    fun cancelRender()
}

/**
 * Translates a project into the engine's snapshot text (format documented in engine/core/engine.h).
 * All tempo maths happens here; the engine only sees frames.
 */
fun engineSnapshot(p: Project, projectDir: String): String = buildString {
    fun frames(ticks: Long) = ticksToFrames(ticks, p.bpm)
    fun path(file: String?) = if (file == null) "-" else "$projectDir/$file"
    val bit = { b: Boolean -> if (b) 1 else 0 }

    val framesPerBeat = 60.0 * io.github.corixien.viddaw.model.SAMPLE_RATE / p.bpm
    append("P $framesPerBeat ${p.beatsPerBar} ${bit(p.loopEnabled)} ${frames(p.loopStart)} ${frames(p.loopEnd)} ")
    append("${dbToLinear(p.masterDb)} ${bit(p.metronome)}\n")

    val anySolo = p.tracks.any { it.solo }
    for (t in p.tracks) {
        val type = when (t.type) {
            TrackType.Audio, TrackType.Video -> 0
            TrackType.Sampler -> 1
            TrackType.Drums -> 2
        }
        val audible = !t.mute && (!anySolo || t.solo)
        append("T ${t.id} $type ${dbToLinear(t.volumeDb)} ${t.pan} ${bit(audible)}\n")
        when (t.type) {
            TrackType.Sampler -> t.sampler.let {
                append("S ${it.root} ${it.tune} ${it.attackMs} ${it.releaseMs} ${dbToLinear(it.gainDb)} ${path(it.file)}\n")
            }
            TrackType.Drums -> t.pads.forEachIndexed { i, pad ->
                append("D $i ${dbToLinear(pad.gainDb)} ${pad.pan} ${pad.tune} ${path(pad.file)}\n")
            }
            else -> {}
        }
        for (c in t.clips) {
            if (c.isMidi) {
                for (n in c.notes) {
                    if (n.start < 0 || n.start >= c.length) continue
                    if (t.type == TrackType.Drums && n.pitch !in FIRST_PAD_NOTE until FIRST_PAD_NOTE + 16) continue
                    val start = frames(c.start + n.start)
                    val end = frames(c.start + minOf(n.start + n.length, c.length))
                    append("N $start ${end - start} ${n.pitch} ${n.velocity}\n")
                }
            } else if (c.file != null) {
                append("C ${frames(c.start)} ${c.offset} ${c.frames} ${dbToLinear(c.gainDb)} ${path(c.file)}\n")
            }
        }
        for (e in t.effects) {
            append("E ${e.id} ${e.type} ${bit(e.enabled)} ${e.params.size}")
            e.params.forEach { append(' ').append(it) }
            append('\n')
        }
    }
}
