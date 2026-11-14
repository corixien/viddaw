package io.github.corixien.viddaw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import io.github.corixien.viddaw.app.Panel
import io.github.corixien.viddaw.app.StudioState
import io.github.corixien.viddaw.engine.PEAK_FRAMES
import io.github.corixien.viddaw.model.Clip
import io.github.corixien.viddaw.model.PPQ
import io.github.corixien.viddaw.model.SAMPLE_RATE
import io.github.corixien.viddaw.model.Track
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.clip
import io.github.corixien.viddaw.model.isMidi
import io.github.corixien.viddaw.model.lengthTicks
import io.github.corixien.viddaw.model.mapTrack
import io.github.corixien.viddaw.model.moveClip
import io.github.corixien.viddaw.model.snap
import io.github.corixien.viddaw.model.ticksPerBar
import io.github.corixien.viddaw.model.ticksToFrames
import io.github.corixien.viddaw.model.trimClipEnd
import io.github.corixien.viddaw.model.trimClipStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val HeaderWidth = 118.dp
private val RulerHeight = 30.dp
private val TrackHeight = 68.dp

fun trackIcon(type: TrackType) = when (type) {
    TrackType.Audio -> Lucide.Mic
    TrackType.Sampler -> Lucide.Piano
    TrackType.Drums -> Lucide.Drum
    TrackType.Video -> Lucide.Film
}

@Composable
fun Timeline(studio: StudioState, modifier: Modifier = Modifier) {
    val vScroll = rememberScrollState()
    // One frame loop drives the playhead, meters and follow-scroll.
    LaunchedEffect(studio) {
        while (true) {
            withFrameNanos { }
            studio.tick()
        }
    }
    Row(modifier) {
        Column(Modifier.width(HeaderWidth).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().height(RulerHeight).pressable { studio.panel = Panel.AddTrack }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.Plus, pal.text, 16.dp)
                Txt(T.track.t, style = type.label)
            }
            Hairline()
            Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                studio.project.tracks.forEachIndexed { i, t -> TrackHeader(studio, t, i) }
                Spacer(Modifier.height(TrackHeight))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
        TimelineCanvas(studio, vScroll, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TrackHeader(studio: StudioState, track: Track, index: Int) {
    val selected = studio.selectedTrack == track.id
    val bg by animateColorAsState(if (selected) pal.surface else Color.Transparent)
    Row(
        Modifier.fillMaxWidth().height(TrackHeight).background(bg)
            .clickable(remember { MutableInteractionSource() }, null) {
                if (selected) studio.panel = Panel.TrackSettings(track.id) else studio.selectedTrack = track.id
            },
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(trackColor(track.color)))
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 8.dp), Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(trackIcon(track.type), pal.dim, 13.dp)
                Txt(track.name, style = type.label, color = if (track.mute) pal.dim else pal.text)
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Latch("M", track.mute, Color(0xFFD9A441), { studio.edit { p -> p.mapTrack(track.id) { it.copy(mute = !it.mute) } } })
                Latch("S", track.solo, Color(0xFF4A90E2), { studio.edit { p -> p.mapTrack(track.id) { it.copy(solo = !it.solo) } } })
                MeterBar({ studio.meterTick; studio.levels[2 + index] }, Modifier.weight(1f).height(5.dp), vertical = false)
            }
        }
    }
    Hairline()
}

private sealed interface Hit {
    data object Ruler : Hit
    data object LoopStart : Hit
    data object LoopEnd : Hit
    data class Empty(val track: Track?) : Hit
    data class OnClip(val clip: Clip, val track: Track, val part: Int) : Hit  // part: 0 body, -1 left edge, 1 right edge
}

@Composable
private fun TimelineCanvas(studio: StudioState, vScroll: ScrollState, modifier: Modifier) {
    val p = pal
    val ty = type
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val fling = remember { arrayOfNulls<Job>(1) }
    val rulerPx = RulerHeight.value * density
    val trackPx = TrackHeight.value * density + density  // header height plus its hairline

    fun ppt() = studio.beatWidth * density / PPQ

    fun hit(pos: Offset): Hit {
        val project = studio.project
        val tick = studio.scrollTick + pos.x / ppt()
        if (pos.y < rulerPx) {
            if (project.loopEnabled) {
                val reach = 18 * density
                val xs = (project.loopStart - studio.scrollTick) * ppt()
                val xe = (project.loopEnd - studio.scrollTick) * ppt()
                if (abs(pos.x - xe) < reach) return Hit.LoopEnd
                if (abs(pos.x - xs) < reach) return Hit.LoopStart
            }
            return Hit.Ruler
        }
        val track = project.tracks.getOrNull(floor((pos.y - rulerPx + vScroll.value) / trackPx).toInt()) ?: return Hit.Empty(null)
        val clip = track.clips.lastOrNull { tick >= it.start && tick < it.start + it.lengthTicks(project.bpm) } ?: return Hit.Empty(track)
        if (clip.id != studio.selectedClip) return Hit.OnClip(clip, track, 0)
        val x0 = ((clip.start - studio.scrollTick) * ppt()).toFloat()
        val x1 = x0 + (clip.lengthTicks(project.bpm) * ppt()).toFloat()
        val edge = min(24 * density, (x1 - x0) / 3)
        return Hit.OnClip(clip, track, if (pos.x - x0 < edge) -1 else if (x1 - pos.x < edge) 1 else 0)
    }

    Spacer(
        modifier.clipToBounds().background(p.sunken)
            .pointerInput(studio) {
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fling[0]?.cancel()
                    val target = hit(down.position)
                    val velocity = VelocityTracker()
                    var total = Offset.Zero
                    var mode = 0  // 0 undecided, 1 scroll, 2 clip drag, 3 loop, 4 scrub, 5 pinch
                    val startClip = (target as? Hit.OnClip)?.clip
                    val grabTick = studio.scrollTick + down.position.x / ppt()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (mode == 2 || mode == 3) studio.endGesture()
                            mode = 5
                            val zoom = event.calculateZoom()
                            val cx = event.calculateCentroid().x
                            val anchor = studio.scrollTick + cx / ppt()
                            studio.beatWidth = (studio.beatWidth * zoom).coerceIn(5f, 260f)
                            studio.scrollTick = max(0.0, anchor - cx / ppt())
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (mode == 5) continue
                        val change = pressed.first()
                        val delta = change.positionChange()
                        total += delta
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (mode == 0 && total.getDistance() > viewConfiguration.touchSlop) {
                            mode = when (target) {
                                is Hit.OnClip -> if (target.clip.id == studio.selectedClip) 2 else 1
                                Hit.LoopStart, Hit.LoopEnd -> 3
                                Hit.Ruler -> 4
                                is Hit.Empty -> 1
                            }
                            if (mode == 2 || mode == 3) studio.beginGesture()
                        }
                        val tick = studio.scrollTick + change.position.x / ppt()
                        val grid = studio.gridTicks()
                        when (mode) {
                            1 -> {
                                studio.scrollTick = max(0.0, studio.scrollTick - delta.x / ppt())
                                vScroll.dispatchRawDelta(-delta.y)
                            }
                            2 -> if (startClip != null && target is Hit.OnClip) {
                                when (target.part) {
                                    -1 -> studio.edit { it.trimClipStart(startClip.id, snap(tick.toLong(), grid)) }
                                    1 -> studio.edit { it.trimClipEnd(startClip.id, snap(tick.toLong(), grid)) }
                                    else -> {
                                        val row = floor((change.position.y - rulerPx + vScroll.value) / trackPx).toInt()
                                        val to = studio.project.tracks.getOrNull(row)?.id
                                        val start = snap(startClip.start + (tick - grabTick).toLong(), grid)
                                        studio.edit { it.moveClip(startClip.id, start, to) }
                                    }
                                }
                            }
                            3 -> {
                                val at = snap(tick.toLong(), if (grid > 0) grid else 1).coerceAtLeast(0)
                                studio.edit {
                                    if (target == Hit.LoopStart) it.copy(loopStart = min(at, it.loopEnd - PPQ))
                                    else it.copy(loopEnd = max(at, it.loopStart + PPQ))
                                }
                            }
                            4 -> studio.seekTick(snap(tick.toLong(), grid))
                        }
                        if (mode != 0) change.consume()
                    }

                    if (mode == 2 || mode == 3) studio.endGesture()
                    if (mode == 1) {
                        val vx = velocity.calculateVelocity().x
                        if (abs(vx) > 300f) {
                            fling[0] = scope.launch {
                                var last = 0f
                                AnimationState(0f, vx).animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                                    studio.scrollTick = max(0.0, studio.scrollTick - (value - last) / ppt())
                                    last = value
                                }
                            }
                        }
                    }
                    if (mode == 0) {
                        val now = down.uptimeMillis
                        val double = now - lastTapTime < 320 && (down.position - lastTapPos).getDistance() < 48 * density
                        lastTapTime = if (double) 0 else now
                        lastTapPos = down.position
                        when (target) {
                            Hit.Ruler, Hit.LoopStart, Hit.LoopEnd -> studio.seekTick(snap(grabTick.toLong(), studio.gridTicks()))
                            is Hit.OnClip -> {
                                if (double && target.clip.isMidi) studio.panel = Panel.NoteEditor(target.clip.id)
                                studio.selectClip(target.clip.id)
                            }
                            is Hit.Empty -> {
                                studio.selectClip(null)
                                target.track?.let { t ->
                                    studio.selectedTrack = t.id
                                    if (double && t.type.isMidi) studio.panel = Panel.NoteEditor(studio.addMidiClip(t, grabTick.toLong()))
                                }
                            }
                        }
                    }
                }
            }
            .drawBehind {
                val project = studio.project
                studio.viewWidthPx = size.width
                val ppt = ppt().toDouble()
                val scroll = studio.scrollTick
                val w = size.width
                val h = size.height
                val scrollY = vScroll.value
                fun x(tick: Long) = ((tick - scroll) * ppt).toFloat()

                // Rows
                project.tracks.forEachIndexed { i, t ->
                    val y = rulerPx + i * trackPx - scrollY
                    if (t.id == studio.selectedTrack) drawRect(p.surface.copy(alpha = if (p.dark) 0.35f else 0.55f), Offset(0f, y), Size(w, trackPx))
                    drawLine(p.line, Offset(0f, y + trackPx), Offset(w, y + trackPx), density)
                }

                // Grid: bars always, beats once they are wide enough to read
                val bar = project.ticksPerBar
                val barPx = bar * ppt
                var labelStep = 1
                while (barPx * labelStep < 56 * density) labelStep *= 2
                val beatPx = PPQ * ppt
                var b = floor(scroll / bar).toLong()
                while (x(b * bar) < w) {
                    val bx = x(b * bar)
                    if (b % labelStep == 0L || barPx > 20 * density) drawLine(p.line, Offset(bx, rulerPx), Offset(bx, h), density)
                    if (beatPx >= 14 * density) {
                        for (beat in 1 until project.beatsPerBar) {
                            val gx = bx + (beat * beatPx).toFloat()
                            drawLine(p.line.copy(alpha = p.line.alpha * 0.45f), Offset(gx, rulerPx), Offset(gx, h), density)
                        }
                    }
                    b++
                }

                // Clips
                project.tracks.forEachIndexed { i, t ->
                    val top = rulerPx + i * trackPx - scrollY + 3 * density
                    val height = trackPx - 7 * density
                    if (top > h || top + height < rulerPx) return@forEachIndexed
                    for (c in t.clips) {
                        val cx = x(c.start)
                        val cw = (c.lengthTicks(project.bpm) * ppt).toFloat()
                        if (cx > w || cx + cw < 0) continue
                        drawClip(studio, c, t, cx, top, cw, height, c.id == studio.selectedClip, measurer, ty.small, density, project.bpm, ppt)
                    }
                    if (studio.recording && studio.recordTrack == t.id) {
                        val from = ((studio.recordStartFrame.toDouble() * project.bpm * PPQ / (60.0 * SAMPLE_RATE) - scroll) * ppt).toFloat()
                        val to = ((studio.positionFrames.toDouble() * project.bpm * PPQ / (60.0 * SAMPLE_RATE) - scroll) * ppt).toFloat()
                        val rw = max(to - from, 2 * density)
                        drawRoundRect(p.record.copy(alpha = 0.85f), Offset(from, top), Size(rw, height), CornerRadius(6 * density))
                        val peaks = studio.recordPeaks
                        if (peaks.isNotEmpty()) {
                            val pxPerPeak = rw / peaks.size
                            val mid = top + height / 2
                            var i2 = 0
                            while (i2 < peaks.size) {
                                val a = max(peaks[i2] * height * 0.46f, density * 0.5f)
                                val px = from + i2 * pxPerPeak
                                drawLine(Color.White, Offset(px, mid - a), Offset(px, mid + a), density * 1.2f)
                                i2 += max(1, (2 * density / pxPerPeak).toInt())
                            }
                        }
                    }
                }

                // Ruler
                drawRect(p.window, Offset.Zero, Size(w, rulerPx))
                if (project.loopEnabled) {
                    val xs = x(project.loopStart)
                    val xe = x(project.loopEnd)
                    drawRect(p.accent.copy(alpha = 0.18f), Offset(xs, 0f), Size(xe - xs, rulerPx))
                    drawRect(p.accent.copy(alpha = 0.05f), Offset(xs, rulerPx), Size(xe - xs, h - rulerPx))
                    for (lx in listOf(xs, xe)) drawRoundRect(p.accent, Offset(lx - 2 * density, 4 * density), Size(4 * density, rulerPx - 8 * density), CornerRadius(2 * density))
                }
                b = floor(scroll / bar).toLong()
                while (x(b * bar) < w) {
                    if (b % labelStep == 0L) {
                        val bx = x(b * bar)
                        drawLine(p.dim.copy(alpha = 0.5f), Offset(bx, rulerPx - 8 * density), Offset(bx, rulerPx), density)
                        drawText(measurer, "${b + 1}", Offset(bx + 5 * density, 7 * density), ty.small.copy(color = p.dim))
                    }
                    b++
                }
                drawLine(p.line, Offset(0f, rulerPx), Offset(w, rulerPx), density)

                // Playhead
                val px = ((studio.positionFrames.toDouble() * project.bpm * PPQ / (60.0 * SAMPLE_RATE) - scroll) * ppt).toFloat()
                if (px in -10f..w + 10f) {
                    val ink = if (studio.recording) p.record else p.text
                    drawLine(ink, Offset(px, rulerPx - 2 * density), Offset(px, h), 1.5f * density)
                    val cap = Path().apply {
                        moveTo(px - 6 * density, rulerPx - 13 * density)
                        lineTo(px + 6 * density, rulerPx - 13 * density)
                        lineTo(px, rulerPx - 3 * density)
                        close()
                    }
                    drawPath(cap, ink)
                }
            },
    )

    // Keep the playhead in view while playing.
    LaunchedEffect(studio) {
        while (true) {
            withFrameNanos { }
            if (!studio.playing) continue
            val widthTicks = studio.viewWidthPx / ppt()
            if (widthTicks <= 0) continue
            val pos = studio.positionTick
            if (pos > studio.scrollTick + widthTicks * 0.92 || pos < studio.scrollTick) studio.scrollTick = max(0.0, pos - widthTicks * 0.08)
        }
    }
}

private fun DrawScope.drawClip(
    studio: StudioState, c: Clip, t: Track, x: Float, top: Float, w: Float, h: Float, selected: Boolean,
    measurer: TextMeasurer, labelStyle: androidx.compose.ui.text.TextStyle, density: Float, bpm: Double, ppt: Double,
) {
    val base = trackColor(t.color)
    val r = CornerRadius(6 * density)
    drawRoundRect(base, Offset(x, top), Size(w, h), r)
    val left = max(x, 0f)
    val right = min(x + w, size.width)
    val labelH = 15 * density
    clipRect(left, top, right, top + h) {
        if (w > 40 * density) {
            val text = measurer.measure(
                c.name.ifEmpty { t.name }, labelStyle.copy(color = Color.White), TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = (w - 12 * density).toInt().coerceAtLeast(1)),
            )
            drawText(text, topLeft = Offset(x + 7 * density, top + 3 * density))
        }
        val bodyTop = top + labelH
        val bodyH = h - labelH - 3 * density
        if (c.isMidi) {
            val notes = c.notes.filter { it.start >= 0 && it.start < c.length }
            if (notes.isNotEmpty()) {
                val lo = notes.minOf { it.pitch }
                val span = max(notes.maxOf { it.pitch } - lo, 6)
                for (n in notes) {
                    val ny = bodyTop + bodyH * (1f - (n.pitch - lo + 0.5f) / (span + 1))
                    val nx = x + (n.start * ppt).toFloat()
                    val nw = max((min(n.length, c.length - n.start) * ppt).toFloat() - density, 2 * density)
                    drawRoundRect(Color.White, Offset(nx, ny - 1.5f * density), Size(nw, 3 * density), CornerRadius(density))
                }
            }
        } else {
            if (c.video != null) {
                // Sprocket holes mark video clips.
                var hx = x + 6 * density
                while (hx < right) {
                    if (hx > left - 8 * density) drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(hx, top + h - 8 * density), Size(5 * density, 4 * density), CornerRadius(density))
                    hx += 11 * density
                }
            }
            val peaks = c.file?.let(studio::peaksFor)
            if (peaks != null && peaks.isNotEmpty()) {
                val framesPerPx = 1.0 / (ppt * bpm * PPQ / (60.0 * SAMPLE_RATE))
                val mid = bodyTop + bodyH / 2
                val step = 2 * density
                var px = left
                while (px < right) {
                    val f0 = c.offset + (px - x) * framesPerPx
                    val i0 = (f0 / PEAK_FRAMES).toInt().coerceIn(0, peaks.lastIndex)
                    val i1 = ((f0 + step * framesPerPx) / PEAK_FRAMES).toInt().coerceIn(i0, peaks.lastIndex)
                    var peak = 0f
                    val stride = max(1, (i1 - i0) / 24)
                    var k = i0
                    while (k <= i1) {
                        if (peaks[k] > peak) peak = peaks[k]
                        k += stride
                    }
                    val a = max(min(peak, 1f) * bodyH * 0.5f, density * 0.5f)
                    drawLine(Color.White.copy(alpha = 0.92f), Offset(px, mid - a), Offset(px, mid + a), density * 1.2f)
                    px += step
                }
            }
        }
    }
    if (selected) {
        val ink = Color.White
        drawRoundRect(ink, Offset(x + density, top + density), Size(w - 2 * density, h - 2 * density), r, style = Stroke(2 * density))
        if (w > 36 * density) {
            for (hx in listOf(x + 5 * density, x + w - 8 * density))
                drawRoundRect(ink, Offset(hx, top + h / 2 - 9 * density), Size(3 * density, 18 * density), CornerRadius(2 * density))
        }
    }
}
