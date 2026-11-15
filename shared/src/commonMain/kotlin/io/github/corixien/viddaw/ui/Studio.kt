package io.github.corixien.viddaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.corixien.viddaw.model.mapClip
import io.github.corixien.viddaw.model.transitionAt
import kotlin.math.abs
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import io.github.corixien.viddaw.app.AppState
import io.github.corixien.viddaw.app.ExportFormat
import io.github.corixien.viddaw.app.Panel
import io.github.corixien.viddaw.app.StudioState
import io.github.corixien.viddaw.model.PPQ
import io.github.corixien.viddaw.model.SAMPLE_RATE
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.clip
import io.github.corixien.viddaw.model.ticksPerBar
import io.github.corixien.viddaw.model.track
import kotlinx.coroutines.delay

@Composable
fun StudioScreen(app: AppState, studio: StudioState) {
    val project = studio.project
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val fullW = maxWidth
            val fullH = maxHeight
            val wide = fullW > fullH
            Column(Modifier.fillMaxSize()) {
                TopBar(app, studio)
                Hairline()
                if (studio.hasVideo && !wide) {
                    VideoBox(app, studio, Modifier.fillMaxWidth().height(minOf(fullW * 9 / 16, fullH * 0.3f)))
                    Hairline()
                }
                Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) {
                        Timeline(studio, Modifier.fillMaxSize())
                        if (project.tracks.isEmpty()) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Txt(T.emptyStudio.t, color = pal.dim)
                                Spacer(Modifier.height(12.dp))
                                Btn(T.addTrack.t, { studio.panel = Panel.AddTrack }, primary = true, icon = Lucide.Plus)
                            }
                        }
                    }
                    if (studio.hasVideo && wide) {
                        Box(Modifier.width(1.dp).fillMaxSize().background(pal.line))
                        VideoBox(app, studio, Modifier.width(fullW * 0.36f).fillMaxSize())
                    }
                }
                Hairline()
                TransportBar(studio)
            }
        }

        AddTrackSheet(studio)
        TempoSheet(studio)
        ExportSheet(studio)
        ClipToolSheet(studio)
        AddOverlaySheet(studio)
        TrackSettingsSheet(studio)
        EffectsSheet(studio)
        NoteEditorSheet(studio)
        PlaySheet(studio)
        Toasts(studio)
        BusyOverlay(studio)
    }
}

/**
 * The picture. The platform plays the video with its colour grade; placement, transitions,
 * texts and shapes are laid over it here, the same way the export composes them.
 * Dragging moves the selected video clip or overlay, two fingers resize and turn it.
 */
@Composable
private fun VideoBox(app: AppState, studio: StudioState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = type.heading
    val segments = studio.videoSegments()
    val overlays = studio.overlaySegments()
    fun now() = studio.positionFrames.toDouble() / SAMPLE_RATE
    fun active(t: Double) = overlays.filter { t >= it.startSec && t < it.startSec + it.durationSec }
    val first = segments.firstOrNull()?.uri
    LaunchedEffect(first) { if (first != null) app.platform.videoAspect(first).takeIf { it > 0f }?.let { studio.videoAspect = it } }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            Modifier.aspectRatio(studio.videoAspect).clipToBounds().pointerInput(studio) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val id = studio.selectedClip
                    val clip = id?.let(studio.project::clip)?.takeIf { it.overlay != null || it.video != null }
                    var travelled = 0f
                    var moving = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val turn = event.calculateRotation()
                            travelled += pan.getDistance()
                            if (!moving && (travelled > viewConfiguration.touchSlop || event.changes.count { it.pressed } > 1)) {
                                moving = true
                                if (clip != null) studio.beginGesture()
                            }
                            if (moving && clip != null) {
                                val dx = pan.x / size.width
                                val dy = pan.y / size.height
                                studio.edit { p ->
                                    p.mapClip(clip.id) { c ->
                                        val o = c.overlay
                                        if (o != null) c.copy(overlay = o.copy(x = (o.x + dx).coerceIn(-1f, 1f), y = (o.y + dy).coerceIn(-1f, 1f), size = (o.size * zoom).coerceIn(0.02f, 0.8f), rotation = o.rotation + turn))
                                        else c.copy(fx = c.fx.copy(x = (c.fx.x + dx).coerceIn(-1f, 1f), y = (c.fx.y + dy).coerceIn(-1f, 1f), scale = (c.fx.scale * zoom).coerceIn(0.2f, 3f), rotation = c.fx.rotation + turn))
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } finally {
                        if (moving && clip != null) studio.endGesture()
                    }
                    if (!moving) {
                        // A tap picks the topmost overlay under the finger.
                        val frame = Size(size.width.toFloat(), size.height.toFloat())
                        active(now()).lastOrNull { o ->
                            val half = overlayExtent(o.overlay, frame)
                            val c = Offset(frame.width / 2 + o.overlay.x * frame.width, frame.height / 2 + o.overlay.y * frame.height)
                            abs(down.position.x - c.x) < half.x + 12.dp.toPx() && abs(down.position.y - c.y) < half.y + 12.dp.toPx()
                        }?.let { studio.selectClip(it.clipId) }
                    }
                }
            },
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val t = now()
                    val seg = segments.firstOrNull { t >= it.startSec && t < it.startSec + it.durationSec } ?: return@graphicsLayer
                    val tr = seg.fx.transitionAt(t - seg.startSec, seg.durationSec)
                    val k = seg.fx.scale * tr.scale
                    scaleX = if (seg.fx.flip) -k else k
                    scaleY = k
                    rotationZ = seg.fx.rotation
                    translationX = (seg.fx.x + tr.dx) * size.width
                    translationY = (seg.fx.y + tr.dy) * size.height
                    alpha = (tr.fade * seg.fx.opacity).coerceIn(0f, 1f)
                },
            ) {
                app.platform.VideoPreview(segments, ::now, studio.playing, Modifier.fillMaxSize())
            }
            Canvas(Modifier.fillMaxSize()) {
                for (o in active(now())) drawOverlay(o.overlay, measurer, style, o.clipId in studio.selection)
            }
        }
        IconBtn(Lucide.Type, { studio.panel = Panel.AddOverlay }, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = Color.White, size = 36.dp)
    }
}

@Composable
private fun TopBar(app: AppState, studio: StudioState) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Lucide.ChevronLeft, { app.closeStudio() })
        Txt(studio.project.name, Modifier.weight(1f).padding(horizontal = 4.dp), type.heading)
        IconBtn(Lucide.Undo2, studio::undo, enabled = studio.canUndo)
        IconBtn(Lucide.Redo2, studio::redo, enabled = studio.canRedo)
        IconBtn(Lucide.Share, { studio.panel = Panel.Export })
    }
}

@Composable
private fun TransportBar(studio: StudioState) {
    val project = studio.project
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(Lucide.SkipBack, studio::toStart)
        IconBtn(if (studio.playing) Lucide.Pause else Lucide.Play, studio::togglePlay, size = 44.dp)
        // Record: a red disc that becomes a stop square while recording.
        Box(Modifier.size(44.dp).pressable(onClick = studio::toggleRecord), contentAlignment = Alignment.Center) {
            Box(Modifier.size(30.dp).border(2.dp, pal.record, CircleShape), contentAlignment = Alignment.Center) {
                val inner by androidx.compose.animation.core.animateDpAsState(if (studio.recording) 5.dp else 11.dp)
                Box(Modifier.size(if (studio.recording) 12.dp else 22.dp).clip(RoundedCornerShape(inner)).background(pal.record))
            }
        }
        Spacer(Modifier.weight(1f))
        // Position as bar.beat and the tempo; tapping opens tempo settings.
        Column(
            Modifier.pressable { studio.panel = Panel.Tempo }.background(pal.sunken, RoundedCornerShape(Radius.control)).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tick = studio.positionTick
            val bar = tick / project.ticksPerBar + 1
            val beat = (tick % project.ticksPerBar) / PPQ + 1
            Txt("$bar.$beat", style = type.digits)
            Txt("${fmt(project.bpm.toFloat(), if (project.bpm % 1.0 == 0.0) 0 else 1)} BPM", style = type.small, color = pal.dim)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBtn(Lucide.Repeat, { studio.edit { it.copy(loopEnabled = !it.loopEnabled) } }, active = project.loopEnabled, size = 38.dp)
            IconBtn(Lucide.Timer, { studio.edit { it.copy(metronome = !it.metronome) } }, active = project.metronome, size = 38.dp)
            IconBtn(Lucide.Magnet, { studio.snapOn = !studio.snapOn }, active = studio.snapOn, size = 38.dp)
        }
    }
}

@Composable
private fun BoxScope.AddTrackSheet(studio: StudioState) {
    val lang = LocalLang.current
    Sheet(studio.panel == Panel.AddTrack, { studio.panel = Panel.None }, T.addTrack.t) {
        fun add(type: TrackType, name: Str) {
            val n = studio.project.tracks.count { it.type == type } + 1
            studio.addTrack(type, "${name.of(lang)} $n")
            studio.panel = Panel.None
        }
        ListRow(T.audioTrack.t, { add(TrackType.Audio, T.audioTrack) }, subtitle = T.audioTrackHint.t, icon = Lucide.Mic)
        ListRow(T.drumTrack.t, { add(TrackType.Drums, T.drumTrack) }, subtitle = T.drumTrackHint.t, icon = Lucide.Drum)
        ListRow(T.samplerTrack.t, { add(TrackType.Sampler, T.samplerTrack) }, subtitle = T.samplerTrackHint.t, icon = Lucide.Piano)
        ListRow(T.videoTrack.t, { add(TrackType.Video, T.videoTrack) }, subtitle = T.videoTrackHint.t, icon = Lucide.Film)
        Hairline(Modifier.padding(vertical = 6.dp))
        ListRow(T.importAudio.t, { studio.panel = Panel.None; studio.importAudio() }, icon = Lucide.Import)
        ListRow(T.importVideo.t, { studio.panel = Panel.None; studio.importVideo() }, icon = Lucide.Video)
        ListRow(T.textOrShape.t, { studio.panel = Panel.AddOverlay }, subtitle = T.textOrShapeHint.t, icon = Lucide.Type)
    }
}

@Composable
private fun BoxScope.TempoSheet(studio: StudioState) {
    val project = studio.project
    Sheet(studio.panel == Panel.Tempo, { studio.panel = Panel.None }, T.tempo.t) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("−", { studio.edit { it.copy(bpm = (it.bpm - 1).coerceAtLeast(40.0)) } }, Modifier.width(52.dp))
            Txt("${fmt(project.bpm.toFloat(), 0)} BPM", Modifier.weight(1f), type.title, align = androidx.compose.ui.text.style.TextAlign.Center)
            Btn("+", { studio.edit { it.copy(bpm = (it.bpm + 1).coerceAtMost(240.0)) } }, Modifier.width(52.dp))
        }
        HSlider(
            ((project.bpm - 40) / 200).toFloat(), { v -> studio.edit { it.copy(bpm = (40 + v * 200).toInt().toDouble()) } },
            Modifier.fillMaxWidth(), onStart = studio::beginGesture, onEnd = studio::endGesture,
        )
        Spacer(Modifier.height(12.dp))
        Txt(T.timeSignature.t, style = type.label, color = pal.dim)
        Spacer(Modifier.height(6.dp))
        val beats = listOf(2, 3, 4, 5, 6, 7)
        Segmented(beats.map { "$it/4" }, beats.indexOf(project.beatsPerBar), { i -> studio.edit { it.copy(beatsPerBar = beats[i]) } }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BoxScope.ExportSheet(studio: StudioState) {
    Sheet(studio.panel == Panel.Export, { studio.panel = Panel.None }, T.export.t) {
        val formats = ExportFormat.entries.filter { !it.video || studio.videoSegments().isNotEmpty() }
        for (f in formats) {
            ListRow(f.label, { studio.export(f) }, icon = if (f.video) Lucide.Film else Lucide.Music)
        }
    }
}

/** Short messages that slide down from the top and leave on their own. */
@Composable
private fun BoxScope.Toasts(studio: StudioState) {
    val text = studio.notice?.t ?: studio.message?.let { "${T.exportDone.t} $it" }
    LaunchedEffect(studio.notice, studio.message) {
        if (studio.notice != null || studio.message != null) {
            delay(3600)
            studio.notice = null
            studio.message = null
        }
    }
    val shown = remember { arrayOf("") }
    if (text != null) shown[0] = text
    AnimatedVisibility(
        text != null,
        Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 58.dp, start = 16.dp, end = 16.dp),
        enter = slideInVertically(spring(dampingRatio = 0.8f)) { -it * 2 },
        exit = slideOutVertically { -it * 2 },
    ) {
        Box(Modifier.background(pal.accent, RoundedCornerShape(Radius.panel)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Txt(shown[0], style = type.label, color = pal.onAccent, maxLines = 3)
        }
    }
}

@Composable
private fun BoxScope.BusyOverlay(studio: StudioState) {
    val busy = studio.busy ?: return
    Box(
        Modifier.fillMaxSize().background(pal.scrim).clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(240.dp).background(pal.surface, RoundedCornerShape(Radius.panel)).padding(20.dp)) {
            Txt(busy.t, style = type.heading)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(pal.sunken, CircleShape)) {
                Box(Modifier.fillMaxWidth(studio.progress.coerceIn(0.02f, 1f)).height(6.dp).background(pal.accent, CircleShape))
            }
        }
    }
}
