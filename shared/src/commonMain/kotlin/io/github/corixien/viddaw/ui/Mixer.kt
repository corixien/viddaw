package io.github.corixien.viddaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import io.github.corixien.viddaw.app.Panel
import io.github.corixien.viddaw.app.StudioState
import io.github.corixien.viddaw.model.BUILTIN_PAD_NAMES
import io.github.corixien.viddaw.model.EFFECT_SPECS
import io.github.corixien.viddaw.model.FIRST_PAD_NOTE
import io.github.corixien.viddaw.model.Track
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.addEffect
import io.github.corixien.viddaw.model.effectSpec
import io.github.corixien.viddaw.model.isMidi
import io.github.corixien.viddaw.model.mapEffect
import io.github.corixien.viddaw.model.mapTrack
import io.github.corixien.viddaw.model.moveEffect
import io.github.corixien.viddaw.model.moveTrack
import io.github.corixien.viddaw.model.removeEffect
import io.github.corixien.viddaw.model.removeTrack
import io.github.corixien.viddaw.model.track

/** The most recent panel of type [P], kept so a sheet still has content while it slides out. */
@Composable
inline fun <reified P : Panel> lastPanel(studio: StudioState): P? {
    val keep = remember { arrayOfNulls<Panel>(1) }
    (studio.panel as? P)?.let { keep[0] = it }
    return keep[0] as? P
}

@Composable
private fun VFader(value: Float, onChange: (Float) -> Unit, onStart: () -> Unit, onEnd: () -> Unit, modifier: Modifier) {
    val p = pal
    val change by rememberUpdatedState(onChange)
    val latest by rememberUpdatedState(value)
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val pad = 14.dp.toPx()
                val startValue = latest
                val startY = down.position.y
                onStart()
                // Relative drag: the cap does not jump to the finger.
                var current = startValue
                while (true) {
                    val e = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!e.pressed) break
                    current = (startValue - (e.position.y - startY) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    change(current)
                    e.consume()
                }
                onEnd()
            }
        },
    ) {
        val pad = 14.dp.toPx()
        val x = size.width / 2
        val h = size.height - 2 * pad
        val y = pad + h * (1 - value.coerceIn(0f, 1f))
        drawRoundRect(p.sunken, Offset(x - 2.dp.toPx(), pad), Size(4.dp.toPx(), h), CornerRadius(2.dp.toPx()))
        val unity = pad + h * 0.25f
        drawLine(p.dim, Offset(x - 9.dp.toPx(), unity), Offset(x + 9.dp.toPx(), unity), 1.dp.toPx())
        val cap = Size(26.dp.toPx(), 16.dp.toPx())
        drawRoundRect(p.line, Offset(x - cap.width / 2 - 1.dp.toPx(), y - cap.height / 2 - 1.dp.toPx()), Size(cap.width + 2.dp.toPx(), cap.height + 2.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawRoundRect(if (p.dark) Color(0xFFE5E5EA) else Color.White, Offset(x - cap.width / 2, y - cap.height / 2), cap, CornerRadius(4.dp.toPx()))
        drawLine(Color(0xFF3A3A3C), Offset(x - 8.dp.toPx(), y), Offset(x + 8.dp.toPx(), y), 1.5.dp.toPx())
    }
}

@Composable
fun BoxScope.MixerSheet(studio: StudioState) {
    Sheet(studio.panel == Panel.Mixer, { studio.panel = Panel.None }, T.mixer.t, padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            studio.project.tracks.forEachIndexed { i, t ->
                Strip(
                    t.name, trackColor(t.color), t.volumeDb,
                    { db -> studio.edit { p -> p.mapTrack(t.id) { it.copy(volumeDb = db) } } },
                    studio, listOf(2 + i),
                ) {
                    HSlider(
                        (t.pan + 1) / 2, { v -> studio.edit { p -> p.mapTrack(t.id) { it.copy(pan = ((v * 2 - 1) * 20).toInt() / 20f) } } },
                        Modifier.fillMaxWidth(), center = true, onStart = studio::beginGesture, onEnd = studio::endGesture,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Latch("M", t.mute, Color(0xFFD9A441), { studio.edit { p -> p.mapTrack(t.id) { it.copy(mute = !it.mute) } } })
                        Latch("S", t.solo, Color(0xFF4A90E2), { studio.edit { p -> p.mapTrack(t.id) { it.copy(solo = !it.solo) } } })
                    }
                    Latch("FX", t.effects.isNotEmpty(), pal.accent.takeIf { !pal.dark } ?: Color(0xFF636366), { studio.panel = Panel.Effects(t.id) }, Modifier.width(56.dp))
                }
            }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(330.dp).background(pal.line))
            Strip(T.master.t, pal.dim, studio.project.masterDb, { db -> studio.edit { it.copy(masterDb = db) } }, studio, listOf(0, 1)) {}
        }
    }
}

@Composable
private fun Strip(
    name: String, color: Color, db: Float, onDb: (Float) -> Unit, studio: StudioState, meters: List<Int>,
    extra: @Composable () -> Unit,
) {
    Column(Modifier.width(72.dp).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(color, CircleShape))
        Txt(name, style = type.small, align = TextAlign.Center)
        Row(Modifier.height(190.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            VFader(dbToFader(db), { onDb(faderToDb(it)) }, studio::beginGesture, studio::endGesture, Modifier.width(40.dp).fillMaxHeight())
            for (m in meters) MeterBar({ studio.meterTick; studio.levels[m] }, Modifier.width(5.dp).fillMaxHeight().padding(vertical = 14.dp))
        }
        Txt(fmtDb(db), style = type.small.copy(fontFeatureSettings = "tnum"), color = pal.dim)
        extra()
    }
}

@Composable
fun BoxScope.TrackSettingsSheet(studio: StudioState) {
    val panel = lastPanel<Panel.TrackSettings>(studio)
    val track = panel?.let { studio.project.track(it.trackId) }
    Sheet(studio.panel is Panel.TrackSettings && track != null, { studio.panel = Panel.None }, T.track.t) {
        if (track == null) return@Sheet
        val id = track.id
        DisposableEffect(id) { onDispose { studio.endGesture() } }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(track.name, { name ->
                studio.beginGesture()
                studio.edit { p -> p.mapTrack(id) { it.copy(name = name.take(24)) } }
            }, Modifier.fillMaxWidth(), T.name.t)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                TrackColors.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(30.dp).pressable { studio.edit { p -> p.mapTrack(id) { it.copy(color = i) } } }.background(c, CircleShape)
                            .then(if (track.color == i) Modifier.border(2.dp, pal.text, CircleShape) else Modifier),
                    )
                }
            }
            SliderRow(
                T.volume.t, fmtDb(track.volumeDb), dbToFader(track.volumeDb),
                { v -> studio.edit { p -> p.mapTrack(id) { it.copy(volumeDb = faderToDb(v)) } } },
                onStart = studio::beginGesture, onEnd = studio::endGesture,
            )
            SliderRow(
                T.pan.t, fmtPan(track.pan), (track.pan + 1) / 2,
                { v -> studio.edit { p -> p.mapTrack(id) { it.copy(pan = ((v * 2 - 1) * 50).toInt() / 50f) } } },
                center = true, onStart = studio::beginGesture, onEnd = studio::endGesture,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("${T.effects.t} (${track.effects.size})", { studio.panel = Panel.Effects(id) }, Modifier.weight(1f), icon = Lucide.SlidersHorizontal)
                when (track.type) {
                    TrackType.Audio -> Btn(T.importAudio.t, { studio.selectedTrack = id; studio.panel = Panel.None; studio.importAudio() }, Modifier.weight(1f), icon = Lucide.Import)
                    TrackType.Video -> Btn(T.importVideo.t, { studio.selectedTrack = id; studio.panel = Panel.None; studio.importVideo() }, Modifier.weight(1f), icon = Lucide.Import)
                    else -> Btn(if (track.type == TrackType.Drums) T.pads.t else T.keys.t, { studio.selectedTrack = id; studio.panel = Panel.Play(id) }, Modifier.weight(1f), icon = Lucide.Keyboard)
                }
            }
            if (track.type.isMidi) {
                Hairline()
                Txt(T.instrument.t, style = type.label, color = pal.dim)
                if (track.type == TrackType.Sampler) SamplerSettings(studio, track) else DrumSettings(studio, track)
            }
            Hairline()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(T.moveUp.t, { studio.edit { it.moveTrack(id, -1) } }, Modifier.weight(1f))
                Btn(T.moveDown.t, { studio.edit { it.moveTrack(id, 1) } }, Modifier.weight(1f))
            }
            Btn(T.deleteTrack.t, {
                studio.panel = Panel.None
                studio.selectedClip = null
                studio.edit { it.removeTrack(id) }
                studio.selectedTrack = studio.project.tracks.firstOrNull()?.id
            }, Modifier.fillMaxWidth(), danger = true, icon = Lucide.Trash2)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SamplerSettings(studio: StudioState, track: Track) {
    val s = track.sampler
    val id = track.id
    fun set(f: (io.github.corixien.viddaw.model.SamplerConfig) -> io.github.corixien.viddaw.model.SamplerConfig) =
        studio.edit { p -> p.mapTrack(id) { it.copy(sampler = f(it.sampler)) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(if (s.file == null) T.builtIn.t else s.name, Modifier.weight(1f))
        Btn(T.loadSample.t, { studio.loadSample(id) })
    }
    val g = studio::beginGesture
    val e = studio::endGesture
    SliderRow(T.rootNote.t, noteName(s.root), (s.root - 24) / 72f, { v -> set { it.copy(root = 24 + (v * 72).toInt()) } }, onStart = g, onEnd = e)
    SliderRow(T.tune.t, "${fmt(s.tune, 1)} st", (s.tune + 12) / 24f, { v -> set { it.copy(tune = ((v * 24 - 12) * 10).toInt() / 10f) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.attack.t, "${fmt(s.attackMs, 0)} ms", s.attackMs / 500f, { v -> set { it.copy(attackMs = (v * 500).coerceAtLeast(1f)) } }, onStart = g, onEnd = e)
    SliderRow(T.release.t, "${fmt(s.releaseMs, 0)} ms", s.releaseMs / 3000f, { v -> set { it.copy(releaseMs = (v * 3000).coerceAtLeast(5f)) } }, onStart = g, onEnd = e)
    SliderRow(T.gain.t, fmtDb(s.gainDb), (s.gainDb + 24) / 36f, { v -> set { it.copy(gainDb = ((v * 36 - 24) * 2).toInt() / 2f) } }, onStart = g, onEnd = e)
}

@Composable
private fun DrumSettings(studio: StudioState, track: Track) {
    var pad by remember(track.id) { mutableIntStateOf(0) }
    val id = track.id
    fun set(f: (io.github.corixien.viddaw.model.DrumPad) -> io.github.corixien.viddaw.model.DrumPad) =
        studio.edit { p -> p.mapTrack(id) { t -> t.copy(pads = t.pads.mapIndexed { i, d -> if (i == pad) f(d) else d }) } }
    // Pad picker, laid out like the pads themselves: lowest row at the bottom.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 3 downTo 0) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in 0..3) {
                val i = row * 4 + col
                val on = i == pad
                Box(
                    Modifier.weight(1f).height(34.dp).pressable { pad = i; studio.audition(id, FIRST_PAD_NOTE + i) }
                        .background(if (on) trackColor(track.color) else pal.sunken, RoundedCornerShape(Radius.control)),
                    contentAlignment = Alignment.Center,
                ) { Txt(padName(track, i), style = type.small, color = if (on) Color.White else pal.text) }
            }
        }
    }
    val d = track.pads[pad]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(padName(track, pad), Modifier.weight(1f))
        Btn(T.loadSample.t, { studio.loadSample(id, pad) })
    }
    val g = studio::beginGesture
    val e = studio::endGesture
    SliderRow(T.gain.t, fmtDb(d.gainDb), (d.gainDb + 24) / 36f, { v -> set { it.copy(gainDb = ((v * 36 - 24) * 2).toInt() / 2f) } }, onStart = g, onEnd = e)
    SliderRow(T.pan.t, fmtPan(d.pan), (d.pan + 1) / 2, { v -> set { it.copy(pan = ((v * 2 - 1) * 50).toInt() / 50f) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.tune.t, "${fmt(d.tune, 1)} st", (d.tune + 12) / 24f, { v -> set { it.copy(tune = ((v * 24 - 12) * 10).toInt() / 10f) } }, center = true, onStart = g, onEnd = e)
}

fun padName(track: Track, pad: Int): String = track.pads.getOrNull(pad)?.takeIf { it.file != null }?.name ?: BUILTIN_PAD_NAMES[pad]

@Composable
fun BoxScope.EffectsSheet(studio: StudioState) {
    val panel = lastPanel<Panel.Effects>(studio)
    val track = panel?.let { studio.project.track(it.trackId) }
    var open by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    Sheet(studio.panel is Panel.Effects && track != null, { studio.panel = Panel.None }, "${T.effects.t}: ${track?.name ?: ""}") {
        if (track == null) return@Sheet
        val id = track.id
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (track.effects.isEmpty() && !adding) Txt(T.noEffects.t, Modifier.padding(vertical = 10.dp), color = pal.dim)
            for (fx in track.effects) {
                val spec = effectSpec(fx.type) ?: continue
                Column(Modifier.fillMaxWidth().background(pal.raised, RoundedCornerShape(Radius.panel)).border(1.dp, pal.line, RoundedCornerShape(Radius.panel)).padding(horizontal = 6.dp)) {
                    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Lucide.Power, { studio.edit { it.mapEffect(id, fx.id) { e -> e.copy(enabled = !e.enabled) } } }, active = fx.enabled, size = 34.dp)
                        Txt(spec.name, Modifier.weight(1f).pressable { open = if (open == fx.id) null else fx.id }.padding(horizontal = 10.dp, vertical = 12.dp), type.label)
                        IconBtn(Lucide.ArrowUp, { studio.edit { it.moveEffect(id, fx.id, -1) } }, size = 34.dp, tint = pal.dim)
                        IconBtn(Lucide.ArrowDown, { studio.edit { it.moveEffect(id, fx.id, 1) } }, size = 34.dp, tint = pal.dim)
                        IconBtn(Lucide.Trash2, { studio.edit { it.removeEffect(id, fx.id) } }, size = 34.dp, tint = pal.record)
                    }
                    AnimatedVisibility(open == fx.id) {
                        Column(Modifier.padding(start = 6.dp, end = 2.dp, bottom = 8.dp)) {
                            spec.params.forEachIndexed { i, ps ->
                                val v = fx.params.getOrElse(i) { ps.default }
                                fun set(nv: Float) = studio.edit { it.mapEffect(id, fx.id) { e -> e.copy(params = spec.params.indices.map { k -> if (k == i) nv else e.params.getOrElse(k) { spec.params[k].default } }) } }
                                if (ps.choices.isNotEmpty()) {
                                    Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Txt(ps.name, Modifier.width(92.dp), type.label, pal.dim)
                                        Segmented(ps.choices, v.toInt(), { set(it.toFloat()) }, Modifier.weight(1f))
                                    }
                                } else {
                                    SliderRow(ps.name, paramText(ps, v), ps.toNormalized(v), { set(ps.fromNormalized(it)) }, onStart = studio::beginGesture, onEnd = studio::endGesture)
                                }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(adding) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pair in EFFECT_SPECS.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (spec in pair) Btn(spec.name, {
                            open = studio.project.nextId
                            studio.edit { it.addEffect(id, spec.type) }
                            adding = false
                        }, Modifier.weight(1f))
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Btn(if (adding) T.cancel.t else T.addEffect.t, { adding = !adding }, Modifier.fillMaxWidth(), primary = !adding, icon = if (adding) null else Lucide.Plus)
            Spacer(Modifier.height(4.dp))
        }
    }
}
