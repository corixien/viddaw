package io.github.corixien.viddaw.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Picture settings of one video clip. Preview and export both read them through the functions below. */
@Serializable
data class VideoFx(
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val temperature: Float = 0f,  // -1 cool .. 1 warm
    val hue: Float = 0f,          // degrees
    val filter: Int = 0,          // index into VIDEO_FILTERS
    val blur: Float = 0f,         // 0..1
    val scale: Float = 1f,
    val x: Float = 0f,            // offset as a fraction of the frame, right and down positive
    val y: Float = 0f,
    val rotation: Float = 0f,     // degrees clockwise
    val opacity: Float = 1f,
    val flip: Boolean = false,
    val transIn: Int = 0,         // index into TRANSITIONS
    val transOut: Int = 0,
    val transInMs: Int = 500,
    val transOutMs: Int = 500,
)

val VIDEO_FILTERS = listOf("None", "Mono", "Sepia", "Invert", "Fade", "Vivid")
val TRANSITIONS = listOf("None", "Fade", "Slide left", "Slide right", "Slide up", "Slide down", "Zoom")

/** A text or shape placed over the picture. Position and size are fractions of the frame. */
@Serializable
data class Overlay(
    val kind: Int = 0,            // 0 text, 1 rectangle, 2 ellipse
    val text: String = "",
    val color: Int = 0,           // index into OVERLAY_COLORS
    val size: Float = 0.12f,      // height as a fraction of the frame height
    val aspect: Float = 1.6f,     // width / height, shapes only
    val x: Float = 0f,            // centre offset from the frame centre
    val y: Float = 0f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val box: Boolean = false,     // text on a dark plate
)

val OVERLAY_COLORS = listOf(0xFFFFFFFF, 0xFF111111, 0xFFE5704B, 0xFFD9A441, 0xFF53B37F, 0xFF4A90E2, 0xFF9B6BD6, 0xFFE0567A)

/** Where a clip is inside its transitions at one moment: brightness factor, offset in frames, scale factor. */
class TransitionState(val fade: Float, val dx: Float, val dy: Float, val scale: Float)

private fun ease(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

/** Transition state [t] seconds into a clip that lasts [duration] seconds. */
fun VideoFx.transitionAt(t: Double, duration: Double): TransitionState {
    var fade = 1f
    var dx = 0f
    var dy = 0f
    var scale = 1f
    fun part(type: Int, progress: Float, sign: Float) {
        val p = ease(progress)
        when (type) {
            1 -> fade *= p
            2 -> dx += sign * (1 - p)
            3 -> dx -= sign * (1 - p)
            4 -> dy += sign * (1 - p)
            5 -> dy -= sign * (1 - p)
            6 -> {
                scale *= 0.6f + 0.4f * p
                fade *= p
            }
        }
    }
    if (transIn != 0) part(transIn, (t * 1000 / transInMs.coerceAtLeast(1)).toFloat(), 1f)
    if (transOut != 0) part(transOut, ((duration - t) * 1000 / transOutMs.coerceAtLeast(1)).toFloat(), -1f)
    return TransitionState(fade, dx, dy, scale)
}

val VideoFx.hasColor get() = brightness != 0f || contrast != 0f || saturation != 0f || temperature != 0f || hue != 0f || filter != 0 || opacity != 1f

/**
 * Colour grade as an affine RGB transform on 0..1 values: three rows of (r, g, b, offset).
 * [fade] darkens towards black and carries opacity and fade transitions.
 */
fun VideoFx.colorMatrix(fade: Float = 1f): FloatArray {
    var m = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
    fun then(n: FloatArray) {
        val r = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..2) r[row * 4 + col] = (0..2).sumOf { (n[row * 4 + it] * m[it * 4 + col]).toDouble() }.toFloat()
            r[row * 4 + 3] = (0..2).sumOf { (n[row * 4 + it] * m[it * 4 + 3]).toDouble() }.toFloat() + n[row * 4 + 3]
        }
        m = r
    }
    fun saturate(s: Float) {
        val lr = 0.2126f * (1 - s)
        val lg = 0.7152f * (1 - s)
        val lb = 0.0722f * (1 - s)
        then(floatArrayOf(lr + s, lg, lb, 0f, lr, lg + s, lb, 0f, lr, lg, lb + s, 0f))
    }
    fun contrast(c: Float) = then(floatArrayOf(c, 0f, 0f, 0.5f - 0.5f * c, 0f, c, 0f, 0.5f - 0.5f * c, 0f, 0f, c, 0.5f - 0.5f * c))

    when (filter) {
        1 -> saturate(0f)
        2 -> then(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0.272f, 0.534f, 0.131f, 0f))
        3 -> then(floatArrayOf(-1f, 0f, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, -1f, 1f))
        4 -> {
            contrast(0.78f)
            saturate(0.75f)
            then(floatArrayOf(1f, 0f, 0f, 0.06f, 0f, 1f, 0f, 0.06f, 0f, 0f, 1f, 0.06f))
        }
        5 -> {
            contrast(1.15f)
            saturate(1.4f)
        }
    }
    if (hue != 0f) {
        val a = hue * PI.toFloat() / 180f
        val c = cos(a)
        val s = sin(a)
        then(floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f,
        ))
    }
    if (saturation != 0f) saturate(1 + saturation)
    if (contrast != 0f) contrast(1 + contrast * 0.8f)
    if (temperature != 0f) then(floatArrayOf(1 + 0.18f * temperature, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1 - 0.18f * temperature, 0f))
    if (brightness != 0f) then(floatArrayOf(1f, 0f, 0f, brightness * 0.5f, 0f, 1f, 0f, brightness * 0.5f, 0f, 0f, 1f, brightness * 0.5f))
    val k = (fade * opacity).coerceIn(0f, 1f)
    if (k != 1f) for (i in m.indices) m[i] *= k
    return m
}
