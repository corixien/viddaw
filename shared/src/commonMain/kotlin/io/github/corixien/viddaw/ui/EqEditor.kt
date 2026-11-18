package io.github.corixien.viddaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.corixien.viddaw.model.EffectSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One draggable point of the EQ: which parameters hold its gain, frequency and Q (mid bands only). */
private class Band(val label: String, val gain: Int, val freq: Int, val q: Int?, val kind: Int)  // kind 0 low shelf, 1 peak, 2 high shelf

// In frequency order. Parameter numbers follow engine/core/effects.cpp.
private val BANDS = listOf(
    Band("1", 1, 2, null, 0), Band("2", 3, 4, 5, 1), Band("3", 12, 13, 14, 1), Band("4", 6, 7, 8, 1), Band("5", 9, 10, null, 2),
)
private const val MIN_HZ = 20f
private const val MAX_HZ = 20000f
private const val RANGE_DB = 18f
private const val RATE = 48000.0

/** Gain in dB at [hz] of one biquad of the engine's EQ (RBJ cookbook, same as Biquad::set). */
private fun biquadDb(kind: Int, freq: Float, q: Float, gainDb: Float, hz: Double): Double {
    val w = 2 * PI * freq.coerceIn(10f, (RATE * 0.45).toFloat()) / RATE
    val c = cos(w)
    val s = sin(w)
    val alpha = s / (2 * maxOf(q, 0.05f))
    val a = 10.0.pow(gainDb / 40.0)
    val sq = 2 * sqrt(a) * alpha
    val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
    when (kind) {
        LOW_PASS -> { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2; a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha }
        HIGH_PASS -> { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2; a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha }
        0 -> {
            b0 = a * ((a + 1) - (a - 1) * c + sq); b1 = 2 * a * ((a - 1) - (a + 1) * c); b2 = a * ((a + 1) - (a - 1) * c - sq)
            a0 = (a + 1) + (a - 1) * c + sq; a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - sq
        }
        2 -> {
            b0 = a * ((a + 1) + (a - 1) * c + sq); b1 = -2 * a * ((a - 1) + (a + 1) * c); b2 = a * ((a + 1) + (a - 1) * c - sq)
            a0 = (a + 1) - (a - 1) * c + sq; a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - sq
        }
        else -> { b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a; a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a }
    }
    val x = 2 * PI * hz / RATE
    val (c1, s1, c2, s2) = listOf(cos(x), sin(x), cos(2 * x), sin(2 * x))
    val nr = b0 + b1 * c1 + b2 * c2
    val ni = -(b1 * s1 + b2 * s2)
    val dr = a0 + a1 * c1 + a2 * c2
    val di = -(a1 * s1 + a2 * s2)
    return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
}

private const val LOW_PASS = 10
private const val HIGH_PASS = 11

/** Combined response of the whole EQ at [hz]. */
private fun responseDb(p: (Int) -> Float, hz: Double): Double {
    var db = 0.0
    if (p(0) > 21) db += biquadDb(HIGH_PASS, p(0), 0.707f, 0f, hz)
    for (b in BANDS) db += biquadDb(b.kind, p(b.freq), b.q?.let(p) ?: 0.707f, p(b.gain), hz)
    if (p(11) < 19900) db += biquadDb(LOW_PASS, p(11), 0.707f, 0f, hz)
    return db
}

private fun xOf(hz: Float, w: Float) = w * (ln(hz / MIN_HZ) / ln(MAX_HZ / MIN_HZ))
private fun hzOf(x: Float, w: Float) = MIN_HZ * (MAX_HZ / MIN_HZ).pow((x / w).coerceIn(0f, 1f))
private fun yOf(db: Float, h: Float) = h / 2 * (1 - db / RANGE_DB)
private fun dbOf(y: Float, h: Float) = (RANGE_DB * (1 - 2 * y / h)).coerceIn(-RANGE_DB, RANGE_DB)

/**
 * The EQ as a graph: five points to drag (left right for frequency, up down for gain),
 * double tap puts a point back. The selected band also gets sliders for fine values and Q.
 */
@Composable
fun EqEditor(spec: EffectSpec, params: List<Float>, set: (Int, Float) -> Unit, onStart: () -> Unit, onEnd: () -> Unit) {
    val p = pal
    val ty = type
    val measurer = rememberTextMeasurer()
    var selected by remember { mutableIntStateOf(1) }
    val current by rememberUpdatedState(params)
    val setter by rememberUpdatedState(set)
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    fun value(i: Int) = current.getOrElse(i) { spec.params[i].default }

    Canvas(
        Modifier.fillMaxWidth().height(190.dp).pointerInput(Unit) {
            var lastTap = 0L
            var lastBand = -1
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val reach = 30.dp.toPx()
                val hit = BANDS.indices.minByOrNull { i ->
                    val b = BANDS[i]
                    (Offset(xOf(value(b.freq), w), yOf(value(b.gain), h)) - down.position).getDistance()
                }?.takeIf { i ->
                    val b = BANDS[i]
                    (Offset(xOf(value(b.freq), w), yOf(value(b.gain), h)) - down.position).getDistance() < reach
                } ?: return@awaitEachGesture
                val b = BANDS[hit]
                selected = hit
                down.consume()
                val double = hit == lastBand && down.uptimeMillis - lastTap < 320
                lastTap = 0
                var moved = 0f
                start()
                if (double) {
                    setter(b.gain, 0f)
                    setter(b.freq, spec.params[b.freq].default)
                    b.q?.let { setter(it, spec.params[it].default) }
                }
                while (true) {
                    val e = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!e.pressed) break
                    val d = e.position - e.previousPosition
                    if (d != Offset.Zero && !double) {
                        moved += d.getDistance()
                        val fs = spec.params[b.freq]
                        setter(b.freq, hzOf(e.position.x, w).coerceIn(fs.min, fs.max))
                        setter(b.gain, (dbOf(e.position.y, h) * 2).roundToInt() / 2f)
                    }
                    e.consume()
                }
                if (moved < viewConfiguration.touchSlop) { lastTap = down.uptimeMillis; lastBand = hit }
                end()
            }
        },
    ) {
        val w = size.width
        val h = size.height
        val hair = 1.dp.toPx()
        drawRect(p.sunken)
        for (db in listOf(-12f, -6f, 6f, 12f)) drawLine(p.line.copy(alpha = 0.5f), Offset(0f, yOf(db, h)), Offset(w, yOf(db, h)), hair)
        for (hz in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
            val x = xOf(hz, w)
            drawLine(p.line.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), hair)
            if (hz == 100f || hz == 1000f || hz == 10000f) {
                val label = measurer.measure(if (hz >= 1000f) "${(hz / 1000).roundToInt()}k" else "${hz.roundToInt()}", ty.small.copy(color = p.dim))
                drawText(label, topLeft = Offset(x + 3.dp.toPx(), h - label.size.height - 2.dp.toPx()))
            }
        }
        drawLine(p.line, Offset(0f, yOf(0f, h)), Offset(w, yOf(0f, h)), hair * 1.5f)

        val curve = Path()
        val steps = 160
        for (i in 0..steps) {
            val x = w * i / steps
            val db = responseDb(::value, hzOf(x, w).toDouble()).toFloat().coerceIn(-RANGE_DB, RANGE_DB)
            if (i == 0) curve.moveTo(x, yOf(db, h)) else curve.lineTo(x, yOf(db, h))
        }
        drawPath(curve, p.text, style = Stroke(2.dp.toPx()))

        BANDS.forEachIndexed { i, b ->
            val c = Offset(xOf(value(b.freq), w), yOf(value(b.gain), h))
            val on = i == selected
            drawCircle(if (on) p.accent else p.raised, if (on) 13.dp.toPx() else 11.dp.toPx(), c)
            drawCircle(p.text, if (on) 13.dp.toPx() else 11.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            val label = measurer.measure(b.label, ty.small.copy(color = if (on) p.onAccent else p.text))
            drawText(label, topLeft = c - Offset(label.size.width / 2f, label.size.height / 2f))
        }
    }

    val band = BANDS[selected]
    Column(Modifier.padding(top = 4.dp)) {
        val gs = spec.params[band.gain]
        val fs = spec.params[band.freq]
        SliderRow("Gain", paramText(gs, value(band.gain)), gs.toNormalized(value(band.gain)), { setter(band.gain, gs.fromNormalized(it)) }, onStart = start, onEnd = end, reset = gs.toNormalized(gs.default))
        SliderRow("Freq", paramText(fs, value(band.freq)), fs.toNormalized(value(band.freq)), { setter(band.freq, fs.fromNormalized(it)) }, onStart = start, onEnd = end, reset = fs.toNormalized(fs.default))
        band.q?.let { qi ->
            val qs = spec.params[qi]
            SliderRow("Q", paramText(qs, value(qi)), qs.toNormalized(value(qi)), { setter(qi, qs.fromNormalized(it)) }, onStart = start, onEnd = end, reset = qs.toNormalized(qs.default))
        }
        for (i in listOf(0, 11)) {
            val s = spec.params[i]
            SliderRow(s.name, paramText(s, value(i)), s.toNormalized(value(i)), { setter(i, s.fromNormalized(it)) }, onStart = start, onEnd = end, reset = s.toNormalized(s.default))
        }
        Spacer(Modifier.height(2.dp))
    }
}
