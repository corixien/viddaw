package io.github.corixien.viddaw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.lucide.*
import io.github.corixien.viddaw.app.Panel
import io.github.corixien.viddaw.app.Spot
import io.github.corixien.viddaw.app.StudioState
import io.github.corixien.viddaw.app.Tool
import io.github.corixien.viddaw.engine.PEAK_FRAMES
import io.github.corixien.viddaw.model.Clip
import io.github.corixien.viddaw.model.PPQ
import io.github.corixien.viddaw.model.SAMPLE_RATE
import io.github.corixien.viddaw.model.Track
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.dbToLinear
import io.github.corixien.viddaw.model.isMidi
import io.github.corixien.viddaw.model.lengthTicks
import io.github.corixien.viddaw.model.mapTrack
import io.github.corixien.viddaw.model.moveClip
import io.github.corixien.viddaw.model.moveClips
import io.github.corixien.viddaw.model.moveTrack
import io.github.corixien.viddaw.model.snap
import io.github.corixien.viddaw.model.ticksPerBar
import io.github.corixien.viddaw.model.trimClipsEnd
import io.github.corixien.viddaw.model.trimClipsStart
import io.github.corixien.viddaw.model.withGroups
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeSource

private val HeaderWidth = 118.dp
val RulerHeight = 30.dp
val TrackHeight = 68.dp

fun trackIcon(type: TrackType) = when (type) {
    TrackType.Audio -> Lucide.Mic
    TrackType.Sampler -> Lucide.Piano
    TrackType.Drums -> Lucide.Drum
    TrackType.Video -> Lucide.Film
}

/** Beats from the project start to the playhead, with frame precision. */
private fun StudioState.positionBeats(): Double = positionFrames * project.bpm / (60.0 * SAMPLE_RATE)

/**
 * The arrangement. The playhead is fixed in the middle and the content moves under it, so
 * scrolling is seeking. Track headers reach to the playhead at the project start and shrink
 * to their normal width as the content scrolls in. Swiping the headers to the right sends
 * them to the right edge and reveals the mixer in their place.
 */
@Composable
fun Timeline(studio: StudioState, modifier: Modifier = Modifier) {
    val vScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    // One frame loop drives the position, meters and with them everything that scrolls.
    LaunchedEffect(studio) {
        while (true) {
            withFrameNanos { }
            studio.tick()
        }
    }
    val mixer = remember { Animatable(if (studio.mixerOpen) 1f else 0f) }
    val settle = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(studio.mixerOpen) { mixer.animateTo(if (studio.mixerOpen) 1f else 0f, settle) }
    val basePx = HeaderWidth.value * density

    BoxWithConstraints(modifier.clipToBounds()) {
        val wPx = constraints.maxWidth.toFloat()

        /** Left edge and width of the header column for the current scroll and mixer progress. */
        fun headerGeometry(): Pair<Float, Float> {
            val atRest = max(basePx, wPx / 2 - (studio.positionBeats() * studio.beatWidth * density).toFloat())
            val m = mixer.value.coerceIn(0f, 1f)
            // Wide headers first travel to the right edge, then narrow; normal ones only travel.
            val split = if (atRest > basePx + 1f) 0.72f else 1f
            val travel = min(m / split, 1f)
            val narrow = if (split < 1f) ((m - split) / (1 - split)).coerceIn(0f, 1f) else 0f
            val width = atRest + (basePx - atRest) * narrow
            return (travel * (wPx - atRest) + (atRest - width)) to width
        }

        TimelineCanvas(studio, vScroll, Modifier.fillMaxSize())

        if (mixer.value > 0.001f) {
            val panelWidth = (wPx - basePx).roundToInt()
            MixerPanel(studio, vScroll, Modifier.layout { measurable, c ->
                val p = measurable.measure(Constraints.fixed(panelWidth, c.maxHeight))
                layout(c.maxWidth, c.maxHeight) { p.place(headerGeometry().first.roundToInt() - panelWidth, 0) }
            })
        }

        HeaderColumn(
            studio, vScroll,
            onSwipe = { dx -> scope.launch { mixer.snapTo((mixer.value + dx / (wPx - basePx)).coerceIn(0f, 1f)) } },
            onSwipeEnd = { velocity ->
                val open = if (abs(velocity) > 500f) velocity > 0 else mixer.value > 0.5f
                studio.mixerOpen = open
                scope.launch { mixer.animateTo(if (open) 1f else 0f, settle) }
            },
            Modifier.layout { measurable, c ->
                val (left, width) = headerGeometry()
                val p = measurable.measure(Constraints.fixed(width.roundToInt(), c.maxHeight))
                layout(c.maxWidth, c.maxHeight) { p.place(left.roundToInt(), 0) }
            },
        )

        ClipToolbar(studio, vScroll, wPx, basePx)
    }
}

@Composable
private fun HeaderColumn(
    studio: StudioState, vScroll: ScrollState, onSwipe: (Float) -> Unit, onSwipeEnd: (Float) -> Unit, modifier: Modifier,
) {
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val trackPx = TrackHeight.value * density + density
    var dragTrack by remember { mutableStateOf<Long?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragFrom by remember { mutableIntStateOf(0) }

    fun dropIndex(): Int = (dragFrom + (dragY / trackPx).roundToInt()).coerceIn(0, max(studio.project.tracks.lastIndex, 0))

    Row(modifier.background(pal.window)) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(RulerHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Lucide.SlidersHorizontal, { studio.mixerOpen = !studio.mixerOpen }, active = studio.mixerOpen, size = 26.dp)
                Spacer(Modifier.width(6.dp))
                Txt(T.mixer.t, style = type.small, color = pal.dim)
            }
            Hairline()
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(studio) {
                    val longMs = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val row = floor((down.position.y + vScroll.value) / trackPx).toInt()
                        val track = studio.project.tracks.getOrNull(row)
                        val began = TimeSource.Monotonic.markNow()
                        val velocity = VelocityTracker()
                        var total = Offset.Zero
                        var mode = 0  // 0 undecided, 1 mixer swipe, 2 vertical scroll, 3 reorder
                        try {
                            while (true) {
                                val event = if (mode == 0 && track != null) {
                                    val left = longMs - began.elapsedNow().inWholeMilliseconds
                                    if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                                } else awaitPointerEvent()
                                if (event == null) {
                                    mode = 3
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragFrom = row
                                    dragY = 0f
                                    dragTrack = track!!.id
                                    studio.selectTrack(track.id)
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (mode == 0 && !change.isConsumed && track != null) {
                                        if (studio.selectedTrack == track.id) studio.panel = Panel.TrackSettings(track.id) else studio.selectTrack(track.id)
                                    }
                                    break
                                }
                                val d = change.positionChange()
                                total += d
                                velocity.addPosition(change.uptimeMillis, change.position)
                                if (mode == 0 && total.getDistance() > viewConfiguration.touchSlop) mode = if (abs(total.x) > abs(total.y)) 1 else 2
                                when (mode) {
                                    1 -> onSwipe(d.x)
                                    2 -> vScroll.dispatchRawDelta(-d.y)
                                    3 -> dragY += d.y
                                }
                                if (mode != 0) change.consume()
                            }
                        } finally {
                            if (mode == 1) onSwipeEnd(velocity.calculateVelocity().x)
                            val id = dragTrack
                            if (id != null) {
                                val delta = dropIndex() - dragFrom
                                dragTrack = null
                                if (delta != 0) studio.edit { it.moveTrack(id, delta) }
                            }
                        }
                    }
                },
            ) {
                Column(Modifier.verticalScroll(vScroll, enabled = false)) {
                    studio.project.tracks.forEachIndexed { i, t ->
                        val dragging = dragTrack == t.id
                        // Rows between the grabbed one and its drop place step aside.
                        val aside = when {
                            dragTrack == null || dragging -> 0f
                            i in (dragFrom + 1)..dropIndex() -> -trackPx
                            i in dropIndex() until dragFrom -> trackPx
                            else -> 0f
                        }
                        val shift by animateFloatAsState(aside, spring(stiffness = Spring.StiffnessMedium))
                        val lift by animateFloatAsState(if (dragging) 1.03f else 1f)
                        Box(
                            Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                                translationY = if (dragging) dragY else shift
                                scaleX = lift
                                scaleY = lift
                            },
                        ) { TrackHeader(studio, t, i, dragging) }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(TrackHeight).pressable { studio.panel = Panel.AddTrack }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Lucide.Plus, pal.text, 16.dp)
                        Txt(T.track.t, style = type.label)
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
    }
}

@Composable
private fun TrackHeader(studio: StudioState, track: Track, index: Int, lifted: Boolean) {
    val selected = studio.selectedTrack == track.id
    val bg by animateColorAsState(if (lifted) pal.raised else if (selected) pal.surface else pal.window)
    Column {
        Row(Modifier.fillMaxWidth().height(TrackHeight).background(bg)) {
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
}

/** The mixer laid on its side: one strip per track row, the master below them. */
@Composable
private fun MixerPanel(studio: StudioState, vScroll: ScrollState, modifier: Modifier) {
    val tnum = type.small.copy(fontFeatureSettings = "tnum")
    Column(modifier.background(pal.window)) {
        Row(Modifier.fillMaxWidth().height(RulerHeight).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(T.volume.t, Modifier.weight(1f), type.small, pal.dim)
            Txt(T.pan.t, style = type.small, color = pal.dim)
        }
        Hairline()
        Column(Modifier.weight(1f).verticalScroll(vScroll)) {
            for (t in studio.project.tracks) {
                Row(Modifier.fillMaxWidth().height(TrackHeight).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.Volume2, pal.dim, 14.dp)
                            HSlider(
                                dbToFader(t.volumeDb), { v -> studio.edit { p -> p.mapTrack(t.id) { it.copy(volumeDb = faderToDb(v)) } } },
                                Modifier.weight(1f), color = trackColor(t.color), onStart = studio::beginGesture, onEnd = studio::endGesture,
                            )
                            Txt(fmtDb(t.volumeDb), Modifier.width(56.dp), tnum, pal.dim, align = TextAlign.End)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.MoveHorizontal, pal.dim, 14.dp)
                            HSlider(
                                (t.pan + 1) / 2, { v -> studio.edit { p -> p.mapTrack(t.id) { it.copy(pan = ((v * 2 - 1) * 20).roundToInt() / 20f) } } },
                                Modifier.weight(1f), center = true, onStart = studio::beginGesture, onEnd = studio::endGesture,
                            )
                            Txt(fmtPan(t.pan), Modifier.width(56.dp), tnum, pal.dim, align = TextAlign.End)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Latch("FX", t.effects.isNotEmpty(), pal.accent.takeIf { !pal.dark } ?: Color(0xFF636366), { studio.panel = Panel.Effects(t.id) }, Modifier.width(40.dp))
                    IconBtn(Lucide.Ellipsis, { studio.panel = Panel.TrackSettings(t.id) }, size = 36.dp, tint = pal.dim)
                }
                Hairline()
            }
            Row(Modifier.fillMaxWidth().height(TrackHeight).padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(T.master.t, Modifier.width(54.dp), type.label)
                Column(Modifier.weight(1f)) {
                    HSlider(
                        dbToFader(studio.project.masterDb), { v -> studio.edit { it.copy(masterDb = faderToDb(v)) } },
                        Modifier.fillMaxWidth(), onStart = studio::beginGesture, onEnd = studio::endGesture,
                    )
                    for (m in 0..1) {
                        MeterBar({ studio.meterTick; studio.levels[m] }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(4.dp), vertical = false)
                        Spacer(Modifier.height(3.dp))
                    }
                }
                Txt(fmtDb(studio.project.masterDb), Modifier.width(56.dp), tnum, pal.dim, align = TextAlign.End)
            }
        }
    }
}

private sealed interface Hit {
    data object Ruler : Hit
    data object LoopStart : Hit
    data object LoopEnd : Hit
    data class Empty(val track: Track?) : Hit
    data class OnClip(val clip: Clip, val track: Track, val part: Int) : Hit  // part: 0 body, -1 left edge, 1 right edge
}

/** Pixels per tick at the current zoom. */
private fun StudioState.ppt(density: Float): Double = beatWidth.toDouble() * density / PPQ

/** Tick shown at the left edge of a timeline [width] pixels wide. Negative near the project start. */
private fun StudioState.leftTick(width: Float, density: Float): Double = positionBeats() * PPQ - width / 2 / ppt(density)

/** Where a clip sits in the timeline box, in pixels, or null if it no longer exists. */
fun clipBounds(studio: StudioState, clipId: Long, width: Float, density: Float, scrollY: Int): Rect? {
    val project = studio.project
    val row = project.tracks.indexOfFirst { t -> t.clips.any { it.id == clipId } }
    if (row < 0) return null
    val clip = project.tracks[row].clips.first { it.id == clipId }
    val ppt = studio.ppt(density)
    val x = ((clip.start - studio.leftTick(width, density)) * ppt).toFloat()
    val top = RulerHeight.value * density + row * (TrackHeight.value * density + density) - scrollY
    return Rect(x, top, x + (clip.lengthTicks(project.bpm) * ppt).toFloat(), top + TrackHeight.value * density)
}

/** Where a long-pressed empty spot sits in the timeline box. */
fun spotOffset(studio: StudioState, spot: Spot, width: Float, density: Float, scrollY: Int): Offset? {
    val row = studio.project.tracks.indexOfFirst { it.id == spot.trackId }
    if (row < 0) return null
    val x = ((spot.tick - studio.leftTick(width, density)) * studio.ppt(density)).toFloat()
    return Offset(x, RulerHeight.value * density + row * (TrackHeight.value * density + density) - scrollY)
}

@Composable
private fun TimelineCanvas(studio: StudioState, vScroll: ScrollState, modifier: Modifier) {
    val p = pal
    val ty = type
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fling = remember { arrayOfNulls<Job>(1) }
    val rulerPx = RulerHeight.value * density
    val trackPx = TrackHeight.value * density + density  // header height plus its hairline

    fun ppt() = studio.ppt(density)

    /** Scrolls the content by [dx] pixels, which moves the playhead through the project. */
    fun scrollBy(dx: Float) {
        val framesPerPx = 60.0 * SAMPLE_RATE / (studio.project.bpm * studio.beatWidth * density)
        studio.seekFrame(studio.positionFrames - (dx * framesPerPx).roundToLong())
    }

    fun hit(pos: Offset, width: Float): Hit {
        val project = studio.project
        val left = studio.leftTick(width, density)
        val tick = left + pos.x / ppt()
        if (pos.y < rulerPx) {
            if (project.loopEnabled) {
                val reach = 18 * density
                val xs = (project.loopStart - left) * ppt()
                val xe = (project.loopEnd - left) * ppt()
                if (abs(pos.x - xe) < reach) return Hit.LoopEnd
                if (abs(pos.x - xs) < reach) return Hit.LoopStart
            }
            return Hit.Ruler
        }
        val track = project.tracks.getOrNull(floor((pos.y - rulerPx + vScroll.value) / trackPx).toInt()) ?: return Hit.Empty(null)
        if (tick < 0) return Hit.Empty(track)
        val clip = track.clips.lastOrNull { tick >= it.start && tick < it.start + it.lengthTicks(project.bpm) } ?: return Hit.Empty(track)
        // Only a clip selected on its own shows edge handles.
        if (studio.selection.size != 1 || clip.id != studio.selectedClip) return Hit.OnClip(clip, track, 0)
        val x0 = ((clip.start - left) * ppt()).toFloat()
        val x1 = x0 + (clip.lengthTicks(project.bpm) * ppt()).toFloat()
        val edge = min(24 * density, (x1 - x0) / 3)
        return Hit.OnClip(clip, track, if (pos.x - x0 < edge) -1 else if (x1 - pos.x < edge) 1 else 0)
    }

    Spacer(
        modifier.clipToBounds().background(p.sunken)
            .pointerInput(studio) {
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                val longMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fling[0]?.cancel()
                    val width = size.width.toFloat()
                    val target = hit(down.position, width)
                    val velocity = VelocityTracker()
                    val began = TimeSource.Monotonic.markNow()
                    var total = Offset.Zero
                    var mode = 0  // 0 undecided, 1 scroll, 2 clip drag, 3 loop handle, 5 pinch
                    var held = false  // a long press already acted
                    val startClip = (target as? Hit.OnClip)?.clip
                    // The content stands still during a clip drag, so this mapping holds for the whole gesture.
                    val leftTick = studio.leftTick(width, density)
                    val grabTick = leftTick + down.position.x / ppt()
                    var moveIds = emptySet<Long>()
                    val canHold = target is Hit.OnClip || (target is Hit.Empty && target.track != null && grabTick >= 0)

                    try {
                        while (true) {
                            val event = if (mode == 0 && !held && canHold) {
                                val left = longMs - began.elapsedNow().inWholeMilliseconds
                                if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) {
                                held = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                when (target) {
                                    is Hit.OnClip -> if (target.clip.id !in studio.selection) studio.toggleClip(target.clip.id)
                                    is Hit.Empty -> target.track?.let {
                                        studio.selectTrack(it.id)
                                        studio.spot = Spot(it.id, snap(grabTick.toLong(), studio.gridTicks()).coerceAtLeast(0))
                                    }
                                    else -> {}
                                }
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (mode == 2 || mode == 3) studio.endGesture()
                                mode = 5
                                // The playhead is the fixed point of the view, so zooming never moves it.
                                studio.beatWidth = (studio.beatWidth * event.calculateZoom()).coerceIn(5f, 260f)
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
                                    is Hit.OnClip -> if (target.clip.id in studio.selection) 2 else 1
                                    Hit.LoopStart, Hit.LoopEnd -> 3
                                    else -> 1
                                }
                                if (mode == 2 || mode == 3) studio.beginGesture()
                                if (mode == 2) moveIds = studio.targets()
                                if (mode == 1) studio.spot = null
                            }
                            val tick = leftTick + change.position.x / ppt()
                            val grid = studio.gridTicks()
                            when (mode) {
                                1 -> {
                                    scrollBy(delta.x)
                                    vScroll.dispatchRawDelta(-delta.y)
                                }
                                2 -> if (startClip != null && target is Hit.OnClip) {
                                    val linked = studio.project.withGroups(setOf(startClip.id))
                                    when (target.part) {
                                        -1 -> studio.edit { it.trimClipsStart(linked, snap(tick.toLong(), grid)) }
                                        1 -> studio.edit { it.trimClipsEnd(linked, snap(tick.toLong(), grid)) }
                                        else -> {
                                            val wanted = snap(startClip.start + (tick - grabTick).toLong(), grid).coerceAtLeast(0)
                                            if (moveIds.size == 1) {
                                                val row = floor((change.position.y - rulerPx + vScroll.value) / trackPx).toInt()
                                                studio.edit { it.moveClip(startClip.id, wanted, studio.project.tracks.getOrNull(row)?.id) }
                                            } else {
                                                val now = studio.project.startOf(startClip.id)
                                                if (now != null) studio.edit { it.moveClips(moveIds, wanted - now) }
                                            }
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
                            }
                            if (mode != 0) change.consume()
                        }
                    } finally {
                        if (mode == 2 || mode == 3) studio.endGesture()
                    }

                    if (mode == 1) {
                        val vx = velocity.calculateVelocity().x
                        if (abs(vx) > 300f) {
                            fling[0] = scope.launch {
                                var last = 0f
                                AnimationState(0f, vx).animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                                    scrollBy(value - last)
                                    last = value
                                }
                            }
                        }
                    }
                    if (mode == 0 && !held) {
                        val now = down.uptimeMillis
                        val double = now - lastTapTime < 320 && (down.position - lastTapPos).getDistance() < 48 * density
                        lastTapTime = if (double) 0 else now
                        lastTapPos = down.position
                        when (target) {
                            Hit.Ruler, Hit.LoopStart, Hit.LoopEnd -> studio.seekTick(snap(grabTick.toLong(), studio.gridTicks()))
                            is Hit.OnClip -> {
                                studio.selectClip(target.clip.id)
                                if (double && target.clip.isMidi) studio.panel = Panel.NoteEditor(target.clip.id)
                                if (double && target.clip.overlay != null) studio.panel = Panel.ClipTool(Tool.Text)
                            }
                            is Hit.Empty -> {
                                studio.selectClip(null)
                                target.track?.let { t ->
                                    studio.selectedTrack = t.id
                                    if (double && t.type.isMidi && grabTick >= 0) studio.panel = Panel.NoteEditor(studio.addMidiClip(t, grabTick.toLong()))
                                }
                            }
                        }
                    }
                }
            }
            .drawBehind {
                val project = studio.project
                val ppt = ppt()
                val w = size.width
                val h = size.height
                val scroll = studio.leftTick(w, density)
                val scrollY = vScroll.value
                fun x(tick: Long) = ((tick - scroll) * ppt).toFloat()
                val x0 = max(x(0), 0f)

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
                val firstBar = max(0L, floor(scroll / bar).toLong())
                var b = firstBar
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
                        val selected = c.id in studio.selection
                        drawClip(studio, c, t, cx, top, cw, height, selected, selected && studio.selection.size == 1, measurer, ty.small, density, project.bpm, ppt)
                    }
                    if (studio.recording && studio.recordTrack == t.id) {
                        val from = ((studio.recordStartFrame.toDouble() * project.bpm * PPQ / (60.0 * SAMPLE_RATE) - scroll) * ppt).toFloat()
                        val rw = max(w / 2 - from, 2 * density)
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

                // A long-pressed empty spot
                studio.spot?.let { s ->
                    val row = project.tracks.indexOfFirst { it.id == s.trackId }
                    if (row >= 0) {
                        val sx = x(s.tick)
                        val top = rulerPx + row * trackPx - scrollY
                        drawLine(p.accent, Offset(sx, top + 4 * density), Offset(sx, top + trackPx - 5 * density), 2 * density)
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
                b = firstBar
                while (x(b * bar) < w) {
                    if (b % labelStep == 0L) {
                        val bx = x(b * bar)
                        drawLine(p.dim.copy(alpha = 0.5f), Offset(bx, rulerPx - 8 * density), Offset(bx, rulerPx), density)
                        drawText(measurer, "${b + 1}", Offset(bx + 5 * density, 7 * density), ty.small.copy(color = p.dim))
                    }
                    b++
                }
                drawLine(p.line, Offset(x0, rulerPx), Offset(w, rulerPx), density)

                // Playhead, always in the middle
                val px = w / 2
                val ink = if (studio.recording) p.record else p.text
                drawLine(ink, Offset(px, rulerPx - 2 * density), Offset(px, h), 1.5f * density)
                val cap = Path().apply {
                    moveTo(px - 6 * density, rulerPx - 13 * density)
                    lineTo(px + 6 * density, rulerPx - 13 * density)
                    lineTo(px, rulerPx - 3 * density)
                    close()
                }
                drawPath(cap, ink)
            },
    )
}

/** Start tick of a clip as the project has it now. */
private fun io.github.corixien.viddaw.model.Project.startOf(id: Long): Long? =
    tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == id }?.start }

private fun DrawScope.drawClip(
    studio: StudioState, c: Clip, t: Track, x: Float, top: Float, w: Float, h: Float, selected: Boolean, handles: Boolean,
    measurer: TextMeasurer, labelStyle: androidx.compose.ui.text.TextStyle, density: Float, bpm: Double, ppt: Double,
) {
    val base = trackColor(t.color)
    val r = CornerRadius(6 * density)
    drawRoundRect(if (c.overlay != null) base.copy(alpha = 0.7f) else base, Offset(x, top), Size(w, h), r)
    val left = max(x, 0f)
    val right = min(x + w, size.width)
    val labelH = 15 * density
    val framesPerPx = 1.0 / (ppt * bpm * PPQ / (60.0 * SAMPLE_RATE))
    clipRect(left, top, right, top + h) {
        val bodyTop = top + labelH
        val bodyH = h - labelH - 3 * density
        if (c.video != null) {
            // Frames of the video, anchored to the source so they stay put while trimming.
            val thumbs = studio.thumbsFor(c.video, c.fileFrames)
            val first = thumbs?.frames?.firstOrNull()
            if (thumbs != null && first != null) {
                val tileW = bodyH * first.width / first.height
                val origin = x - (c.offset / framesPerPx).toFloat()
                var k = floor((left - origin) / tileW).toInt()
                while (origin + k * tileW < right) {
                    val tx = origin + k * tileW
                    val sec = (tx - origin + tileW / 2) * framesPerPx / SAMPLE_RATE
                    val frame = thumbs.frames[(sec / thumbs.stepSec).toInt().coerceIn(0, thumbs.frames.lastIndex)]
                    drawImage(
                        frame, IntOffset.Zero, IntSize(frame.width, frame.height),
                        IntOffset(tx.roundToInt(), bodyTop.roundToInt()), IntSize(tileW.roundToInt() + 1, bodyH.roundToInt()),
                    )
                    k++
                }
            }
            // Sprocket holes mark video clips.
            var hx = x + 6 * density
            while (hx < right) {
                if (hx > left - 8 * density) drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(hx, top + h - 3.5f * density), Size(5 * density, 2 * density), CornerRadius(density))
                hx += 11 * density
            }
        }
        if (w > 40 * density) {
            val text = measurer.measure(
                c.name.ifEmpty { t.name }, labelStyle.copy(color = Color.White), TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = (w - 12 * density).toInt().coerceAtLeast(1)),
            )
            drawText(text, topLeft = Offset(x + 7 * density, top + 3 * density))
        }
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
        } else if (c.overlay != null) {
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x + 7 * density, bodyTop + bodyH / 2 - 1.5f * density), Size(max(w - 14 * density, 0f), 3 * density), CornerRadius(density))
        } else {
            val peaks = c.file?.let(studio::peaksFor)
            if (peaks != null && peaks.isNotEmpty()) {
                val gain = dbToLinear(c.gainDb)
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
                    val a = max(min(peak * gain, 1f) * bodyH * 0.5f, density * 0.5f)
                    drawLine(Color.White.copy(alpha = 0.92f), Offset(px, mid - a), Offset(px, mid + a), density * 1.2f)
                    px += step
                }
            }
            // Fades darken the corner they take away.
            val shade = Color.Black.copy(alpha = 0.3f)
            if (c.fadeIn > 0) {
                val fw = (c.fadeIn / framesPerPx).toFloat()
                drawPath(Path().apply { moveTo(x, top); lineTo(x + fw, top); lineTo(x, top + h); close() }, shade)
                drawLine(Color.White, Offset(x, top + h), Offset(x + fw, top), density)
            }
            if (c.fadeOut > 0) {
                val fw = (c.fadeOut / framesPerPx).toFloat()
                drawPath(Path().apply { moveTo(x + w, top); lineTo(x + w - fw, top); lineTo(x + w, top + h); close() }, shade)
                drawLine(Color.White, Offset(x + w - fw, top), Offset(x + w, top + h), density)
            }
        }
        // Two dots mark a clip that belongs to a group.
        if (c.group != 0L && w > 26 * density) {
            for (i in 0..1) drawCircle(Color.White, 2 * density, Offset(x + w - 8 * density - i * 6 * density, top + 9 * density))
        }
    }
    if (selected) {
        val ink = Color.White
        drawRoundRect(ink, Offset(x + density, top + density), Size(w - 2 * density, h - 2 * density), r, style = Stroke(2 * density))
        if (handles && w > 36 * density) {
            for (hx in listOf(x + 5 * density, x + w - 8 * density))
                drawRoundRect(ink, Offset(hx, top + h / 2 - 9 * density), Size(3 * density, 18 * density), CornerRadius(2 * density))
        }
    }
}
