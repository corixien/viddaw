package io.github.corixien.viddaw.engine

/** MP3 encoding through Shine (LGPL, separate libshine.so); see engine/android/mp3_jni.cpp. */
object Mp3 {
    init {
        System.loadLibrary("viddaw_mp3")
    }

    /** Encodes a 16-bit stereo 48 kHz WAV at [kbps]. */
    external fun encode(wav: String, out: String, kbps: Int): Boolean
}
