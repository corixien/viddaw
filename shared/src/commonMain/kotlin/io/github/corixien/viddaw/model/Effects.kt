package io.github.corixien.viddaw.model

import kotlin.math.ln
import kotlin.math.pow

/**
 * Parameter layout of every effect. The index of a parameter in [EffectSpec.params] is its
 * index in the engine (engine/core/effects.cpp), so order must match there.
 */
data class ParamSpec(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val log: Boolean = false,
    val choices: List<String> = emptyList(),
) {
    /** Slider position 0..1 to value; logarithmic for frequencies and times. */
    fun fromNormalized(n: Float): Float =
        if (log) min * (max / min).pow(n.coerceIn(0f, 1f)) else min + (max - min) * n.coerceIn(0f, 1f)

    fun toNormalized(v: Float): Float =
        if (log) (ln(v.coerceIn(min, max) / min) / ln(max / min)) else (v.coerceIn(min, max) - min) / (max - min)
}

data class EffectSpec(val type: String, val name: String, val params: List<ParamSpec>) {
    fun defaults(): List<Float> = params.map { it.default }
}

private fun hz(name: String, min: Float, max: Float, default: Float) = ParamSpec(name, min, max, default, "Hz", log = true)
private fun ms(name: String, min: Float, max: Float, default: Float) = ParamSpec(name, min, max, default, "ms", log = true)
private fun db(name: String, min: Float, max: Float, default: Float) = ParamSpec(name, min, max, default, "dB")
private fun pct(name: String, default: Float, min: Float = 0f, max: Float = 100f) = ParamSpec(name, min, max, default, "%")

val EFFECT_SPECS: List<EffectSpec> = listOf(
    EffectSpec(
        "eq", "EQ",
        listOf(
            hz("Low cut", 20f, 1000f, 20f),
            db("Low gain", -18f, 18f, 0f), hz("Low freq", 40f, 600f, 120f),
            db("Mid 1 gain", -18f, 18f, 0f), hz("Mid 1 freq", 100f, 5000f, 500f), ParamSpec("Mid 1 Q", 0.3f, 8f, 1f, log = true),
            db("Mid 2 gain", -18f, 18f, 0f), hz("Mid 2 freq", 500f, 12000f, 3000f), ParamSpec("Mid 2 Q", 0.3f, 8f, 1f, log = true),
            db("High gain", -18f, 18f, 0f), hz("High freq", 2000f, 16000f, 8000f),
            hz("High cut", 1000f, 20000f, 20000f),
            db("Mid 3 gain", -18f, 18f, 0f), hz("Mid 3 freq", 200f, 8000f, 1500f), ParamSpec("Mid 3 Q", 0.3f, 8f, 1f, log = true),
        ),
    ),
    EffectSpec(
        "comp", "Compressor",
        listOf(
            db("Threshold", -60f, 0f, -18f), ParamSpec("Ratio", 1f, 20f, 4f, ":1", log = true),
            ms("Attack", 0.1f, 200f, 10f), ms("Release", 10f, 2000f, 120f),
            db("Knee", 0f, 24f, 6f), db("Makeup", 0f, 24f, 0f), pct("Mix", 100f),
        ),
    ),
    EffectSpec("limiter", "Limiter", listOf(db("Ceiling", -12f, 0f, -1f), ms("Release", 10f, 1000f, 100f), db("Gain", 0f, 24f, 0f))),
    EffectSpec(
        "gate", "Gate",
        listOf(db("Threshold", -80f, 0f, -45f), ms("Attack", 0.1f, 50f, 1f), ParamSpec("Hold", 0f, 500f, 50f, "ms"), ms("Release", 10f, 2000f, 150f), db("Range", -80f, 0f, -80f)),
    ),
    EffectSpec(
        "reverb", "Reverb",
        listOf(pct("Size", 50f), ParamSpec("Decay", 0.2f, 10f, 2f, "s", log = true), pct("Damping", 50f), ParamSpec("Pre-delay", 0f, 200f, 10f, "ms"), hz("Low cut", 20f, 1000f, 100f), pct("Mix", 25f)),
    ),
    EffectSpec(
        "delay", "Delay",
        listOf(ms("Time", 1f, 2000f, 375f), pct("Feedback", 35f, max = 95f), ParamSpec("Mode", 0f, 1f, 0f, choices = listOf("Stereo", "Ping-pong")), hz("Low cut", 20f, 2000f, 100f), hz("High cut", 500f, 20000f, 6000f), pct("Mix", 25f)),
    ),
    EffectSpec("chorus", "Chorus", listOf(ParamSpec("Rate", 0.05f, 5f, 0.8f, "Hz", log = true), pct("Depth", 40f), ParamSpec("Delay", 5f, 30f, 12f, "ms"), pct("Mix", 50f))),
    EffectSpec("flanger", "Flanger", listOf(ParamSpec("Rate", 0.02f, 5f, 0.3f, "Hz", log = true), pct("Depth", 60f), pct("Feedback", 50f, min = -95f, max = 95f), pct("Mix", 50f))),
    EffectSpec("phaser", "Phaser", listOf(ParamSpec("Rate", 0.02f, 5f, 0.4f, "Hz", log = true), pct("Depth", 70f), pct("Feedback", 50f, max = 95f), hz("Center", 200f, 4000f, 800f), pct("Mix", 50f))),
    EffectSpec("filter", "Filter", listOf(ParamSpec("Type", 0f, 3f, 0f, choices = listOf("Low-pass", "High-pass", "Band-pass", "Notch")), hz("Cutoff", 20f, 20000f, 1000f), pct("Resonance", 20f))),
    EffectSpec("dist", "Distortion", listOf(ParamSpec("Type", 0f, 2f, 0f, choices = listOf("Soft", "Hard", "Fuzz")), db("Drive", 0f, 40f, 12f), hz("Tone", 500f, 20000f, 8000f), db("Output", -24f, 12f, -6f), pct("Mix", 100f))),
    EffectSpec("width", "Stereo Width", listOf(pct("Width", 100f, max = 200f))),
    EffectSpec("deesser", "De-Esser", listOf(hz("Frequency", 2000f, 12000f, 6000f), db("Threshold", -60f, 0f, -30f), db("Range", 0f, 24f, 8f))),
)

fun effectSpec(type: String): EffectSpec? = EFFECT_SPECS.firstOrNull { it.type == type }
