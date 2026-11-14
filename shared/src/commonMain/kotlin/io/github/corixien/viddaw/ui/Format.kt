package io.github.corixien.viddaw.ui

import io.github.corixien.viddaw.model.MIN_DB
import io.github.corixien.viddaw.model.ParamSpec
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

fun fmt(v: Float, decimals: Int = 1): String {
    val scale = 10.0.pow(decimals)
    val r = (abs(v) * scale).roundToLong()
    val whole = r / scale.toLong()
    val sign = if (v < 0 && r != 0L) "-" else ""
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${(r % scale.toLong()).toString().padStart(decimals, '0')}"
}

fun fmtDb(db: Float): String = if (db <= MIN_DB) "-∞ dB" else "${fmt(db)} dB"

fun fmtPan(pan: Float): String = when {
    abs(pan) < 0.01f -> "C"
    pan < 0 -> "L${(-pan * 100).toInt()}"
    else -> "R${(pan * 100).toInt()}"
}

fun paramText(spec: ParamSpec, v: Float): String = when {
    spec.choices.isNotEmpty() -> spec.choices[v.toInt().coerceIn(0, spec.choices.lastIndex)]
    spec.unit == "Hz" && v >= 1000 -> "${fmt(v / 1000, 1)} kHz"
    abs(v) >= 100 -> "${fmt(v, 0)} ${spec.unit}"
    abs(v) >= 10 -> "${fmt(v, 1)} ${spec.unit}"
    else -> "${fmt(v, 2)} ${spec.unit}"
}

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun noteName(pitch: Int): String = "${NOTE_NAMES[pitch % 12]}${pitch / 12 - 1}"
fun isBlackKey(pitch: Int): Boolean = (pitch % 12) in setOf(1, 3, 6, 8, 10)

/** Fader travel 0..1 to dB: unity at three quarters, +6 dB at the top, silence at the bottom. */
fun faderToDb(n: Float): Float {
    val v = n.coerceIn(0f, 1f)
    return if (v >= 0.75f) (v - 0.75f) / 0.25f * 6f else MIN_DB * (1f - v / 0.75f).pow(2)
}

fun dbToFader(db: Float): Float =
    if (db >= 0f) 0.75f + (db.coerceAtMost(6f) / 6f) * 0.25f else 0.75f * (1f - sqrt(db.coerceAtLeast(MIN_DB) / MIN_DB))
