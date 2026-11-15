package io.github.corixien.viddaw.engine

/** JNI surface of libviddaw_engine.so (engine/android/jni.cpp). */
object Native {
    init {
        System.loadLibrary("viddaw_engine")
    }

    external fun start(): Boolean
    external fun stop()
    external fun sync(snapshot: String)
    external fun play()
    external fun pause()
    external fun seek(frame: Long)
    external fun position(): Long
    external fun isPlaying(): Boolean
    external fun noteOn(track: Long, pitch: Int, velocity: Int)
    external fun noteOff(track: Long, pitch: Int)
    external fun meters(out: FloatArray): Int
    external fun startInput(): Boolean
    external fun stopInput()
    external fun inputLevel(): Float
    external fun latencyFrames(): Int
    external fun startRecording(path: String): Boolean
    external fun stopRecording(): LongArray
    external fun recordingPeaks(from: Int): FloatArray
    external fun peaks(path: String): FloatArray
    external fun fileFrames(path: String): Long
    external fun stretch(src: String, dst: String, speed: Double, semitones: Double): Long
    external fun renderToFile(path: String, start: Long, end: Long, bits: Int): Boolean
    external fun renderProgress(): Float
    external fun cancelRender()
    external fun importBegin(path: String, rate: Int, channels: Int): Boolean
    external fun importWrite(pcm: ShortArray, frames: Int)
    external fun importEnd(): Long
}

class NativeEngine : AudioEngine {
    override fun start() = Native.start()
    override fun stop() = Native.stop()
    override fun sync(snapshot: String) = Native.sync(snapshot)
    override fun play() = Native.play()
    override fun pause() = Native.pause()
    override fun seek(frame: Long) = Native.seek(frame)
    override fun position() = Native.position()
    override fun isPlaying() = Native.isPlaying()
    override fun noteOn(track: Long, pitch: Int, velocity: Int) = Native.noteOn(track, pitch, velocity)
    override fun noteOff(track: Long, pitch: Int) = Native.noteOff(track, pitch)
    override fun meters(out: FloatArray) = Native.meters(out)
    override fun startRecording(path: String) = Native.startRecording(path)
    override fun stopRecording() = Native.stopRecording().let { Recording(it[0], it[1]) }
    override fun recordingPeaks(from: Int) = Native.recordingPeaks(from)
    override fun latencyFrames() = Native.latencyFrames()
    override fun peaks(path: String) = Native.peaks(path)
    override fun stretch(src: String, dst: String, speed: Double, semitones: Double) = Native.stretch(src, dst, speed, semitones)
    override fun renderToFile(path: String, startFrame: Long, endFrame: Long, bits: Int) = Native.renderToFile(path, startFrame, endFrame, bits)
    override fun renderProgress() = Native.renderProgress()
    override fun cancelRender() = Native.cancelRender()
}
