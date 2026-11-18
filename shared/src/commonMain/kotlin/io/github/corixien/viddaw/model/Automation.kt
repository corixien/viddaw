package io.github.corixien.viddaw.model

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.sqrt

/** Fader travel 0..1 to dB: unity at three quarters, +6 dB at the top, silence at the bottom. */
fun faderToDb(n: Float): Float {
    val v = n.coerceIn(0f, 1f)
    return if (v >= 0.75f) (v - 0.75f) / 0.25f * 6f else MIN_DB * (1f - v / 0.75f).pow(2)
}

fun dbToFader(db: Float): Float =
    if (db >= 0f) 0.75f + (db.coerceAtMost(6f) / 6f) * 0.25f else 0.75f * (1f - sqrt(db.coerceAtLeast(MIN_DB) / MIN_DB))

/** One breakpoint of an automation lane: [value] runs 0..1 over the range of what the lane drives. */
@Serializable
data class AutoPoint(val tick: Long, val value: Float)

/**
 * A curve that drives one control of a track while the project plays. [target] is
 * "vol", "pan", "fx:<effect id>:<parameter index>" on sound tracks, or "v:<field>" on video tracks.
 * With a lane, the control follows the curve instead of its fixed setting.
 */
@Serializable
data class Lane(val id: Long, val target: String, val points: List<AutoPoint> = emptyList())

/** What a lane can drive, as the picker shows it. [group] is the effect name or empty. */
class AutoTarget(val id: String, val label: String, val group: String = "")

/** Video fields a lane can drive: (id, English label, min, max, default). Blur stays fixed because the export cannot animate it. */
val VIDEO_AUTO = listOf(
    Triple("x", "Position X", -1f to 1f), Triple("y", "Position Y", -1f to 1f), Triple("scale", "Scale", 0.2f to 3f),
    Triple("rotation", "Rotation", -180f to 180f), Triple("opacity", "Opacity", 0f to 1f),
    Triple("brightness", "Brightness", -1f to 1f), Triple("contrast", "Contrast", -1f to 1f),
    Triple("saturation", "Saturation", -1f to 1f), Triple("temperature", "Temperature", -1f to 1f), Triple("hue", "Hue", -180f to 180f),
)

fun Track.autoTargets(): List<AutoTarget> =
    if (type == TrackType.Video) VIDEO_AUTO.map { AutoTarget("v:${it.first}", it.second) }
    else listOf(AutoTarget("vol", "Volume"), AutoTarget("pan", "Pan")) + effects.flatMap { e ->
        val spec = effectSpec(e.type) ?: return@flatMap emptyList()
        spec.params.mapIndexedNotNull { i, ps -> if (ps.choices.isEmpty()) AutoTarget("fx:${e.id}:$i", ps.name, spec.name) else null }
    }

/** Whether the lane still points at something that exists on [track]. */
fun Track.hasTarget(lane: Lane): Boolean = autoTargets().any { it.id == lane.target }

/** The value a lane position stands for, in the units of the control; null if the target is gone. */
fun Track.laneValue(target: String, n: Float): Float? {
    val v = n.coerceIn(0f, 1f)
    return when {
        target == "vol" -> dbToLinear(faderToDb(v))
        target == "pan" -> v * 2 - 1
        target.startsWith("fx:") -> {
            val (id, index) = target.removePrefix("fx:").split(':').let { it[0].toLong() to it[1].toInt() }
            val spec = effects.firstOrNull { it.id == id }?.let { effectSpec(it.type) } ?: return null
            spec.params.getOrNull(index)?.fromNormalized(v)
        }
        target.startsWith("v:") -> VIDEO_AUTO.firstOrNull { it.first == target.removePrefix("v:") }?.let { it.third.first + (it.third.second - it.third.first) * v }
        else -> null
    }
}

/** Lane position (0..1) of the control's own setting, the starting height of a new point. */
fun Track.laneRest(target: String): Float = when {
    target == "vol" -> dbToFader(volumeDb)
    target == "pan" -> (pan + 1) / 2
    target.startsWith("fx:") -> {
        val (id, index) = target.removePrefix("fx:").split(':').let { it[0].toLong() to it[1].toInt() }
        val e = effects.firstOrNull { it.id == id }
        val spec = e?.let { effectSpec(it.type) }
        spec?.params?.getOrNull(index)?.let { it.toNormalized(e.params.getOrElse(index) { _ -> it.default }) } ?: 0.5f
    }
    target == "v:scale" || target == "v:opacity" -> VIDEO_AUTO.first { it.first == target.removePrefix("v:") }.let { (1f - it.third.first) / (it.third.second - it.third.first) }
    else -> 0.5f
}

/** Lane value at [tick], linear between points; null for a lane without points. */
fun Lane.valueAt(tick: Double): Float? {
    if (points.isEmpty()) return null
    if (tick <= points.first().tick) return points.first().value
    if (tick >= points.last().tick) return points.last().value
    val i = points.indexOfFirst { it.tick > tick }
    val a = points[i - 1]
    val b = points[i]
    return a.value + (b.value - a.value) * ((tick - a.tick) / (b.tick - a.tick)).toFloat()
}

fun Project.addLane(trackId: Long, target: String): Project {
    val track = track(trackId) ?: return this
    if (track.lanes.any { it.target == target }) return this
    return mapTrack(trackId) { it.copy(lanes = it.lanes + Lane(nextId, target)) }.copy(nextId = nextId + 1)
}

fun Project.removeLane(trackId: Long, laneId: Long): Project = mapTrack(trackId) { t -> t.copy(lanes = t.lanes.filter { it.id != laneId }) }

fun Project.mapLane(trackId: Long, laneId: Long, f: (Lane) -> Lane): Project =
    mapTrack(trackId) { t -> t.copy(lanes = t.lanes.map { if (it.id == laneId) f(it) else it }) }

/** A video clip's picture settings with the automation lanes of its track laid over them, [sec] seconds into the timeline. */
class VideoAuto(val field: String, val seconds: DoubleArray, val values: FloatArray)

fun VideoFx.withAuto(autos: List<VideoAuto>, sec: Double): VideoFx {
    var fx = this
    for (a in autos) {
        if (a.seconds.isEmpty()) continue
        val v = if (sec <= a.seconds.first()) a.values.first() else if (sec >= a.seconds.last()) a.values.last() else {
            var i = 1
            while (a.seconds[i] <= sec) i++
            a.values[i - 1] + (a.values[i] - a.values[i - 1]) * ((sec - a.seconds[i - 1]) / (a.seconds[i] - a.seconds[i - 1])).toFloat()
        }
        fx = when (a.field) {
            "x" -> fx.copy(x = v)
            "y" -> fx.copy(y = v)
            "scale" -> fx.copy(scale = v)
            "rotation" -> fx.copy(rotation = v)
            "opacity" -> fx.copy(opacity = v)
            "brightness" -> fx.copy(brightness = v)
            "contrast" -> fx.copy(contrast = v)
            "saturation" -> fx.copy(saturation = v)
            "temperature" -> fx.copy(temperature = v)
            "hue" -> fx.copy(hue = v)
            else -> fx
        }
    }
    return fx
}
