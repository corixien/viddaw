package io.github.corixien.viddaw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import io.github.corixien.viddaw.app.Panel
import io.github.corixien.viddaw.app.StudioState
import io.github.corixien.viddaw.model.Clip
import io.github.corixien.viddaw.model.FIRST_PAD_NOTE
import io.github.corixien.viddaw.model.Note
import io.github.corixien.viddaw.model.PAD_COUNT
import io.github.corixien.viddaw.model.PPQ
import io.github.corixien.viddaw.model.Track
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.clip
import io.github.corixien.viddaw.model.mapClip
import io.github.corixien.viddaw.model.ticksPerBar
import io.github.corixien.viddaw.model.track
import io.github.corixien.viddaw.model.trackOfClip
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val GRID_DIVS = listOf(1L, 2L, 4L, 8L)  // subdivisions of a beat
private val GRID_LABELS = listOf("1/4", "1/8", "1/16", "1/32")

/** Play and record buttons repeated inside sheets that cover the transport bar. */
@Composable
private fun SheetTransport(studio: StudioState, record: Boolean) {
    if (record) IconBtn(Lucide.Circle, studio::toggleRecord, active = studio.recording, activeColor = pal.record, tint = pal.record)
    IconBtn(if (studio.playing) Lucide.Pause else Lucide.Play, studio::togglePlay)
}

@Composable
fun BoxScope.NoteEditorSheet(studio: StudioState) {
    val panel = lastPanel<Panel.NoteEditor>(studio)
    val clip = panel?.let { studio.project.clip(it.clipId) }
    val track = clip?.let { studio.project.trackOfClip(it.id) }
    val showing = studio.panel is Panel.NoteEditor
    LaunchedEffect(showing, clip == null) { if (showing && clip == null) studio.panel = Panel.None }
    var grid by remember { mutableIntStateOf(2) }
    Sheet(
        showing && clip != null, { studio.panel = Panel.None }, track?.name, maxHeightFraction = 0.92f,
        padding = PaddingValues(0.dp), actions = { SheetTransport(studio, record = false) },
    ) {
        if (clip == null || track == null) return@Sheet
        val drums = track.type == TrackType.Drums
        NoteGrid(studio, clip, track, PPQ / GRID_DIVS[grid], Modifier.fillMaxWidth().height(if (drums) 422.dp else 400.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(T.grid.t, Modifier.width(64.dp), type.label, pal.dim)
            Segmented(GRID_LABELS, grid, { grid = it }, Modifier.weight(1f))
        }
    }
}

/**
 * Piano roll, or a step grid of pads for drum tracks. Tap an empty cell to add a note,
 * tap a note to remove it, drag a note to move it, drag its right end to resize it.
 */
@Composable
private fun NoteGrid(studio: StudioState, clip: Clip, track: Track, grid: Long, modifier: Modifier) {
    val p = pal
    val ty = type
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val drums = track.type == TrackType.Drums
    val rows = if (drums) PAD_COUNT else 88
    val topPitch = if (drums) FIRST_PAD_NOTE + PAD_COUNT - 1 else 108
    val rowPx = (if (drums) 25f else 20f) * density
    val keyPx = (if (drums) 84f else 42f) * density
    val rulerPx = 22f * density
    val beatPx = 72f * density
    val ppt = beatPx / PPQ
    val color = trackColor(track.color)

    var scrollTick by remember(clip.id) { mutableDoubleStateOf(0.0) }
    var scrollY by remember(clip.id) {
        // Open on the notes already there, or around middle C.
        val focus = clip.notes.map { it.pitch }.average().takeIf { !it.isNaN() } ?: 62.0
        mutableFloatStateOf(if (drums) 0f else ((topPitch - focus - 8).toFloat() * rowPx).coerceAtLeast(0f))
    }
    val clipState = androidx.compose.runtime.rememberUpdatedState(clip)
    val gridState = androidx.compose.runtime.rememberUpdatedState(grid)

    fun setNotes(f: (List<Note>) -> List<Note>) = studio.edit { it.mapClip(clip.id) { c -> c.copy(notes = f(c.notes)) } }

    Canvas(
        modifier.clipToBounds().background(p.sunken).pointerInput(clip.id) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val c = clipState.value
                val g = gridState.value
                val maxScrollY = max(0f, rows * rowPx - (size.height - rulerPx))
                fun pitchAt(y: Float) = topPitch - floor((y - rulerPx + scrollY) / rowPx).toInt()
                fun tickAt(x: Float) = scrollTick + (x - keyPx) / ppt
                val pitch = pitchAt(down.position.y).coerceIn(topPitch - rows + 1, topPitch)

                if (down.position.x < keyPx) {
                    // The key column plays the note for as long as it is held.
                    studio.noteOn(pitch, 100, track.id)
                    waitForUpOrCancellation()
                    studio.noteOff(pitch, track.id)
                    return@awaitEachGesture
                }
                val tick = tickAt(down.position.x)
                val index = if (down.position.y < rulerPx) -1 else c.notes.indexOfLast { it.pitch == pitch && tick >= it.start && tick < it.start + it.length }
                val grabbed = c.notes.getOrNull(index)
                val resize = grabbed != null && !drums && (grabbed.start + grabbed.length - tick) * ppt < 14 * density
                var total = Offset.Zero
                var moved = false
                while (true) {
                    val e = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!e.pressed) break
                    val d = e.positionChange()
                    total += d
                    if (!moved && total.getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        if (grabbed != null) studio.beginGesture()
                    }
                    if (!moved) continue
                    e.consume()
                    if (grabbed == null) {
                        scrollTick = max(0.0, scrollTick - d.x / ppt)
                        scrollY = (scrollY - d.y).coerceIn(0f, maxScrollY)
                    } else if (resize) {
                        val end = io.github.corixien.viddaw.model.snap(tickAt(e.position.x).toLong(), g)
                        setNotes { list -> list.mapIndexed { i, n -> if (i == index) n.copy(length = max(end - n.start, g)) else n } }
                    } else {
                        val start = io.github.corixien.viddaw.model.snap(grabbed.start + (total.x / ppt).toLong(), g).coerceIn(0, max(c.length - g, 0))
                        val np = pitchAt(e.position.y).coerceIn(topPitch - rows + 1, topPitch)
                        val before = studio.project.clip(c.id)?.notes?.getOrNull(index)
                        if (before != null && (before.start != start || before.pitch != np)) {
                            if (before.pitch != np) studio.audition(track.id, np)
                            setNotes { list -> list.mapIndexed { i, n -> if (i == index) n.copy(start = start, pitch = np) else n } }
                        }
                    }
                }
                if (moved) {
                    if (grabbed != null) studio.endGesture()
                } else if (down.position.y >= rulerPx) {
                    if (grabbed != null) {
                        setNotes { list -> list.filterIndexed { i, _ -> i != index } }
                    } else if (tick >= 0 && tick < c.length) {
                        val start = (tick.toLong() / g) * g
                        studio.audition(track.id, pitch)
                        setNotes { it + Note(start, g, pitch) }
                    }
                } else {
                    studio.seekTick(c.start + max(0.0, tick).toLong())
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        fun x(tick: Long) = keyPx + ((tick - scrollTick) * ppt).toFloat()
        fun y(pitch: Int) = rulerPx + (topPitch - pitch) * rowPx - scrollY

        // Rows
        for (r in 0 until rows) {
            val pitch = topPitch - r
            val ry = y(pitch)
            if (ry > h || ry + rowPx < rulerPx) continue
            val shade = if (drums) (pitch - FIRST_PAD_NOTE) / 4 % 2 == 1 else isBlackKey(pitch)
            if (shade) drawRect(p.line.copy(alpha = p.line.alpha * 0.5f), Offset(keyPx, ry), Size(w - keyPx, rowPx))
            drawLine(p.line.copy(alpha = p.line.alpha * 0.5f), Offset(keyPx, ry + rowPx), Offset(w, ry + rowPx), density * 0.5f)
        }
        // Grid lines
        val bar = studio.project.ticksPerBar
        var t = (scrollTick.toLong() / grid) * grid
        while (x(t) < w) {
            val gx = x(t)
            if (gx >= keyPx) {
                val alpha = if (t % bar == 0L) 1.6f else if (t % PPQ == 0L) 0.9f else 0.4f
                drawLine(p.line.copy(alpha = min(1f, p.line.alpha * alpha)), Offset(gx, rulerPx), Offset(gx, h), density)
            }
            t += grid
        }
        // Past the end of the clip
        val endX = x(clip.length)
        if (endX < w) drawRect(p.scrim.copy(alpha = 0.25f), Offset(max(endX, keyPx), rulerPx), Size(w - max(endX, keyPx), h - rulerPx))
        // Notes
        for (n in clip.notes) {
            if (n.start < 0 || n.start >= clip.length || n.pitch > topPitch || n.pitch <= topPitch - rows) continue
            val nx = x(n.start)
            val nw = max(n.length * ppt - 2 * density, 4 * density)
            val ny = y(n.pitch)
            if (nx > w || nx + nw < keyPx || ny > h || ny + rowPx < rulerPx) continue
            drawRoundRect(color, Offset(nx + density, ny + 2 * density), Size(nw, rowPx - 4 * density), CornerRadius(4 * density))
        }
        // Playhead
        val px = x(studio.positionTick - clip.start)
        if (px in keyPx..w) drawLine(p.text, Offset(px, rulerPx), Offset(px, h), 1.5f * density)

        // Ruler
        drawRect(p.surface, Offset(keyPx, 0f), Size(w - keyPx, rulerPx))
        var beat = floor(scrollTick / PPQ).toLong()
        while (x(beat * PPQ) < w) {
            val bx = x(beat * PPQ)
            if (bx >= keyPx - 20 * density && beat >= 0) {
                val beats = studio.project.beatsPerBar
                val absolute = (clip.start + beat * PPQ) / PPQ
                val label = if (absolute % beats == 0L) "${absolute / beats + 1}" else "${absolute / beats + 1}.${absolute % beats + 1}"
                drawText(measurer, label, Offset(bx + 4 * density, 4 * density), ty.small.copy(color = p.dim))
            }
            beat++
        }
        drawLine(p.line, Offset(keyPx, rulerPx), Offset(w, rulerPx), density)

        // Key column
        drawRect(p.surface, Offset.Zero, Size(keyPx, h))
        for (r in 0 until rows) {
            val pitch = topPitch - r
            val ry = y(pitch)
            if (ry > h || ry + rowPx < rulerPx) continue
            if (drums) {
                drawText(measurer, padName(track, pitch - FIRST_PAD_NOTE), Offset(8 * density, ry + 5 * density), ty.small.copy(color = p.text), size = Size(keyPx - 10 * density, rowPx))
            } else {
                if (isBlackKey(pitch)) drawRoundRect(if (p.dark) Color.Black else Color(0xFF3A3A3C), Offset(0f, ry + density), Size(keyPx * 0.62f, rowPx - 2 * density), CornerRadius(3 * density))
                if (pitch % 12 == 0) drawText(measurer, noteName(pitch), Offset(keyPx - 24 * density, ry + 3 * density), ty.small.copy(color = p.dim))
            }
            drawLine(p.line, Offset(0f, ry + rowPx), Offset(keyPx, ry + rowPx), density * 0.5f)
        }
        drawRect(p.surface, Offset.Zero, Size(keyPx, rulerPx))
        drawLine(p.line, Offset(keyPx, 0f), Offset(keyPx, h), density)
    }
}

@Composable
fun BoxScope.PlaySheet(studio: StudioState) {
    val panel = lastPanel<Panel.Play>(studio)
    val track = panel?.let { studio.project.track(it.trackId) }
    Sheet(
        studio.panel is Panel.Play && track != null, { studio.panel = Panel.None }, track?.name,
        actions = { SheetTransport(studio, record = true) },
    ) {
        if (track == null) return@Sheet
        if (track.type == TrackType.Drums) PadGrid(studio, track) else Keyboard(studio, track)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PadGrid(studio: StudioState, track: Track) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 3 downTo 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (col in 0..3) {
                val i = row * 4 + col
                var down by remember { mutableStateOf(false) }
                val bg by animateColorAsState(if (down) trackColor(track.color) else pal.raised, androidx.compose.animation.core.tween(if (down) 0 else 220))
                Box(
                    Modifier.weight(1f).height(76.dp).background(bg, RoundedCornerShape(Radius.panel))
                        .pointerInput(track.id, i) {
                            awaitEachGesture {
                                val d = awaitFirstDown()
                                d.consume()
                                // Higher on the pad is louder.
                                val velocity = (127 - (d.position.y / size.height) * 60).toInt().coerceIn(50, 127)
                                down = true
                                studio.noteOn(FIRST_PAD_NOTE + i, velocity, track.id)
                                waitForUpOrCancellation()
                                down = false
                                studio.noteOff(FIRST_PAD_NOTE + i, track.id)
                            }
                        }
                        .padding(8.dp),
                    contentAlignment = Alignment.BottomStart,
                ) { Txt(padName(track, i), style = type.small, color = if (down) Color.White else pal.dim, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun Keyboard(studio: StudioState, track: Track) {
    val p = pal
    val ty = type
    val measurer = rememberTextMeasurer()
    var octave by remember { mutableIntStateOf(4) }
    val held = remember { mutableStateMapOf<Long, Int>() }
    val whites = listOf(0, 2, 4, 5, 7, 9, 11)
    val blackAfter = listOf(0, 1, 3, 4, 5)  // white-key indices followed by a black key
    val whiteCount = 14

    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("−", { octave = (octave - 1).coerceAtLeast(1) }, Modifier.width(52.dp))
        Txt("C$octave – B${octave + 1}", Modifier.weight(1f), type.label, align = androidx.compose.ui.text.style.TextAlign.Center)
        Btn("+", { octave = (octave + 1).coerceAtMost(6) }, Modifier.width(52.dp))
    }
    Canvas(
        Modifier.fillMaxWidth().height(190.dp).pointerInput(track.id) {
            fun pitchAt(pos: Offset): Int {
                val base = (octave + 1) * 12
                val ww = size.width / whiteCount.toFloat()
                val wi = (pos.x / ww).toInt().coerceIn(0, whiteCount - 1)
                if (pos.y < size.height * 0.6f) {
                    // Black keys sit across the boundary to the right of some white keys.
                    for (k in listOf(wi - 1, wi)) {
                        if (k < 0 || k % 7 !in blackAfter) continue
                        val centre = (k + 1) * ww
                        if (kotlin.math.abs(pos.x - centre) < ww * 0.3f) return base + (k / 7) * 12 + whites[k % 7] + 1
                    }
                }
                return base + (wi / 7) * 12 + whites[wi % 7]
            }
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (c in event.changes) {
                        val id = c.id.value
                        if (c.pressed) {
                            val pitch = pitchAt(c.position)
                            val old = held[id]
                            if (old != pitch) {
                                if (old != null) studio.noteOff(old, track.id)
                                studio.noteOn(pitch, 100, track.id)
                                held[id] = pitch
                            }
                            c.consume()
                        } else {
                            held.remove(id)?.let { studio.noteOff(it, track.id) }
                        }
                    }
                }
            }
        },
    ) {
        val base = (octave + 1) * 12
        val ww = size.width / whiteCount
        val down = held.values.toSet()
        val color = trackColor(track.color)
        for (k in 0 until whiteCount) {
            val pitch = base + (k / 7) * 12 + whites[k % 7]
            drawRoundRect(
                if (pitch in down) color else if (p.dark) Color(0xFFE5E5EA) else Color.White,
                Offset(k * ww + 1.dp.toPx(), 0f), Size(ww - 2.dp.toPx(), size.height), CornerRadius(5.dp.toPx()),
            )
            if (k % 7 == 0) drawText(measurer, noteName(pitch), Offset(k * ww + 6.dp.toPx(), size.height - 20.dp.toPx()), ty.small.copy(color = Color(0xFF6E6E73)))
        }
        for (k in 0 until whiteCount - 1) {
            if (k % 7 !in blackAfter) continue
            val pitch = base + (k / 7) * 12 + whites[k % 7] + 1
            val bw = ww * 0.6f
            drawRoundRect(
                if (pitch in down) color else Color(0xFF1C1C1E),
                Offset((k + 1) * ww - bw / 2, 0f), Size(bw, size.height * 0.6f), CornerRadius(4.dp.toPx()),
            )
        }
    }
}
