package io.github.corixien.viddaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
                ClipBar(studio)
                Hairline()
                TransportBar(studio)
            }
        }

        AddTrackSheet(studio)
        TempoSheet(studio)
        ExportSheet(studio)
        MixerSheet(studio)
        TrackSettingsSheet(studio)
        EffectsSheet(studio)
        NoteEditorSheet(studio)
        PlaySheet(studio)
        Toasts(studio)
        BusyOverlay(studio)
    }
}

@Composable
private fun VideoBox(app: AppState, studio: StudioState, modifier: Modifier) {
    Box(modifier.background(Color.Black)) {
        app.platform.VideoPreview(studio.videoSegments(), { studio.positionFrames.toDouble() / SAMPLE_RATE }, studio.playing, Modifier.fillMaxSize())
    }
}

@Composable
private fun TopBar(app: AppState, studio: StudioState) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Lucide.ChevronLeft, { app.closeStudio() })
        Txt(studio.project.name, Modifier.weight(1f).padding(horizontal = 4.dp), type.heading)
        IconBtn(Lucide.Undo2, studio::undo, enabled = studio.canUndo)
        IconBtn(Lucide.Redo2, studio::redo, enabled = studio.canRedo)
        IconBtn(Lucide.SlidersVertical, { studio.panel = Panel.Mixer })
        IconBtn(Lucide.Share, { studio.panel = Panel.Export })
    }
}

/** Actions for the selected clip, or quick actions for the selected track. Slides in above the transport. */
@Composable
private fun ClipBar(studio: StudioState) {
    val clip = studio.selectedClip?.let(studio.project::clip)
    val track = studio.selectedTrack?.let(studio.project::track)
    AnimatedVisibility(clip != null || track != null, enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium))) {
        Column {
            Hairline()
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (clip != null) {
                    BarAction(Lucide.Scissors, T.split.t, studio::splitSelected)
                    BarAction(Lucide.Copy, T.duplicate.t, studio::duplicateSelected)
                    if (clip.isMidi) BarAction(Lucide.Pencil, T.edit.t) { studio.panel = Panel.NoteEditor(clip.id) }
                    Spacer(Modifier.weight(1f))
                    IconBtn(Lucide.Trash2, studio::deleteSelected, tint = pal.record)
                } else if (track != null) {
                    when (track.type) {
                        TrackType.Audio -> BarAction(Lucide.Import, T.importAudio.t) { studio.importAudio() }
                        TrackType.Video -> BarAction(Lucide.Import, T.importVideo.t) { studio.importVideo() }
                        else -> {
                            BarAction(Lucide.Plus, T.newClip.t) { studio.panel = Panel.NoteEditor(studio.addMidiClip(track, studio.positionTick)) }
                            BarAction(Lucide.Keyboard, if (track.type == TrackType.Drums) T.pads.t else T.keys.t) { studio.panel = Panel.Play(track.id) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    BarAction(Lucide.SlidersHorizontal, T.effects.t) { studio.panel = Panel.Effects(track.id) }
                }
            }
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).pressable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, pal.text, 17.dp)
        Txt(label, style = type.label)
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
        IconBtn(Lucide.Repeat, { studio.edit { it.copy(loopEnabled = !it.loopEnabled) } }, active = project.loopEnabled)
        IconBtn(Lucide.Timer, { studio.edit { it.copy(metronome = !it.metronome) } }, active = project.metronome)
        IconBtn(Lucide.Magnet, { studio.snapOn = !studio.snapOn }, active = studio.snapOn)
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
        val formats = ExportFormat.entries.filter { !it.video || studio.hasVideo }
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
