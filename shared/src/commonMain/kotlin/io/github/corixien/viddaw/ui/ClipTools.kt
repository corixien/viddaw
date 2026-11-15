package io.github.corixien.viddaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import io.github.corixien.viddaw.app.Panel
import io.github.corixien.viddaw.app.StudioState
import io.github.corixien.viddaw.app.Tool
import io.github.corixien.viddaw.model.Clip
import io.github.corixien.viddaw.model.OVERLAY_COLORS
import io.github.corixien.viddaw.model.Overlay
import io.github.corixien.viddaw.model.SAMPLE_RATE
import io.github.corixien.viddaw.model.TrackType
import io.github.corixien.viddaw.model.VideoFx
import io.github.corixien.viddaw.model.clip
import io.github.corixien.viddaw.model.clips
import io.github.corixien.viddaw.model.isMidi
import io.github.corixien.viddaw.model.track
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private class Action(val icon: ImageVector, val label: String, val danger: Boolean = false, val run: () -> Unit)

/**
 * Floating actions for the selected clips, or for a long-pressed empty spot. It hovers above
 * its anchor and follows it while the timeline scrolls. Frequent actions are icons; the rest
 * unfolds from the last button.
 */
@Composable
fun ClipToolbar(studio: StudioState, vScroll: ScrollState, widthPx: Float, headerPx: Float) {
    val density = LocalDensity.current.density
    val clip = studio.selectedClip?.let(studio.project::clip)
    val spot = studio.spot
    val shown = (clip != null || spot != null) && studio.panel == Panel.None && !studio.mixerOpen
    var more by remember(studio.selection, spot) { mutableStateOf(false) }
    val anchor = remember { arrayOf(Rect.Zero) }

    val quick = ArrayList<Action>()
    val extra = ArrayList<Action>()
    if (clip != null) {
        val targets = studio.project.clips(studio.targets())
        val hasVideo = targets.any { it.video != null }
        fun tool(t: Tool) = { studio.panel = Panel.ClipTool(t) }
        if (clip.isMidi) quick += Action(Lucide.Pencil, T.edit.t) { studio.panel = Panel.NoteEditor(clip.id) }
        if (clip.overlay != null) quick += Action(Lucide.Type, T.edit.t, run = tool(Tool.Text))
        quick += Action(Lucide.Scissors, T.split.t, run = studio::splitSelected)
        quick += Action(Lucide.Copy, T.copy.t, run = studio::copySelected)
        quick += Action(Lucide.CopyPlus, T.duplicate.t, run = studio::duplicateSelected)
        quick += Action(Lucide.Trash2, T.delete.t, danger = true, run = studio::deleteSelected)
        if (clip.video != null) {
            extra += Action(Lucide.Palette, T.color.t, run = tool(Tool.Color))
            extra += Action(Lucide.Sparkles, T.look.t, run = tool(Tool.Look))
            extra += Action(Lucide.Move, T.transform.t, run = tool(Tool.Transform))
            extra += Action(Lucide.Blend, T.transition.t, run = tool(Tool.Transition))
        }
        if (targets.any { it.isAudio }) {
            extra += Action(Lucide.TrendingUp, T.fade.t, run = tool(Tool.Fade))
            extra += Action(Lucide.Volume2, T.gain.t, run = tool(Tool.Gain))
            extra += Action(Lucide.Activity, T.normalize.t) { studio.normalizeSelected() }
        }
        if (!hasVideo && targets.any { it.isAudio || it.isMidi }) {
            extra += Action(Lucide.MoveHorizontal, T.stretch.t, run = tool(Tool.Stretch))
            extra += Action(Lucide.Music, T.transpose.t, run = tool(Tool.Pitch))
        }
        extra += Action(Lucide.Repeat, T.loopClip.t, run = studio::loopSelected)
        if (studio.selection.size > 1) extra += Action(Lucide.Group, T.group.t) { studio.groupSelected(true) }
        if (studio.selectionGrouped) extra += Action(Lucide.Ungroup, T.ungroup.t) { studio.groupSelected(false) }
    } else if (spot != null) {
        val track = studio.project.track(spot.trackId)
        if (studio.clipboard.isNotEmpty()) extra += Action(Lucide.ClipboardPaste, T.paste.t) { studio.paste(spot.trackId, spot.tick) }
        when (track?.type) {
            TrackType.Audio -> extra += Action(Lucide.Import, T.importAudio.t) { studio.spot = null; studio.seekTick(spot.tick); studio.importAudio() }
            TrackType.Video -> {
                extra += Action(Lucide.Import, T.importVideo.t) { studio.spot = null; studio.seekTick(spot.tick); studio.importVideo() }
                extra += Action(Lucide.Type, T.textOrShape.t) { studio.spot = null; studio.seekTick(spot.tick); studio.panel = Panel.AddOverlay }
            }
            TrackType.Sampler, TrackType.Drums -> extra += Action(Lucide.Plus, T.newClip.t) {
                studio.spot = null
                studio.panel = Panel.NoteEditor(studio.addMidiClip(track, spot.tick))
            }
            null -> {}
        }
    }
    // Keep the last content while the bar animates out.
    val kept = remember { arrayOf<List<Action>>(emptyList(), emptyList()) }
    if (shown) {
        kept[0] = quick
        kept[1] = extra
    }

    AnimatedVisibility(
        shown,
        Modifier.layout { measurable, c ->
            val p = measurable.measure(Constraints())
            layout(c.maxWidth, c.maxHeight) {
                val scrollY = vScroll.value
                studio.selectedClip?.let { clipBounds(studio, it, widthPx, density, scrollY) }?.let { anchor[0] = it }
                    ?: studio.spot?.let { spotOffset(studio, it, widthPx, density, scrollY) }?.let { anchor[0] = Rect(it.x, it.y, it.x, it.y + TrackHeight.toPx()) }
                val a = anchor[0]
                val gap = 6.dp.toPx()
                val edge = 8.dp.toPx()
                val centre = (maxOf(a.left, headerPx) + minOf(a.right, widthPx)) / 2
                val maxX = (c.maxWidth - edge - p.width).coerceAtLeast(edge)
                val x = (centre - p.width / 2).coerceIn(edge, maxX)
                val above = a.top - gap - p.height
                val y = if (above >= RulerHeight.toPx()) above else a.bottom + gap
                p.place(x.roundToInt(), y.roundToInt().coerceIn(0, (c.maxHeight - p.height).coerceAtLeast(0)))
            }
        },
        enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), initialScale = 0.8f),
        exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.9f),
    ) {
        val shape = RoundedCornerShape(Radius.panel)
        Column(Modifier.clip(shape).background(pal.raised).border(1.dp, pal.line, shape).padding(4.dp)) {
            if (kept[0].isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (a in kept[0]) IconBtn(a.icon, a.run, tint = if (a.danger) pal.record else null)
                    if (kept[1].isNotEmpty()) IconBtn(Lucide.Ellipsis, { more = !more }, active = more)
                }
            }
            AnimatedVisibility(more || kept[0].isEmpty()) {
                Column {
                    if (kept[0].isNotEmpty()) Hairline(Modifier.width(236.dp).padding(vertical = 4.dp))
                    for (pair in kept[1].chunked(2)) Row {
                        for (a in pair) Row(
                            Modifier.width(118.dp).height(38.dp).pressable(onClick = a.run).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(a.icon, pal.text, 16.dp)
                            Txt(a.label, style = type.small)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Txt(text, Modifier.padding(top = 6.dp), type.label, pal.dim)
}

/** A wrapping set of exclusive choices. */
@Composable
private fun Choices(names: List<String>, selected: Int, perRow: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in names.indices.chunked(perRow)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in row) Btn(names[i], { onSelect(i) }, Modifier.weight(1f), primary = i == selected)
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Sliders for the tool chosen from the clip toolbar. Changes apply to every selected clip they fit. */
@Composable
fun BoxScope.ClipToolSheet(studio: StudioState) {
    val panel = lastPanel<Panel.ClipTool>(studio)
    val clip = studio.selectedClip?.let(studio.project::clip)
    val tool = panel?.tool
    val title = when (tool) {
        Tool.Fade -> T.fade
        Tool.Gain -> T.gain
        Tool.Stretch -> T.stretch
        Tool.Pitch -> T.transpose
        Tool.Color -> T.color
        Tool.Look -> T.look
        Tool.Transform -> T.transform
        Tool.Transition -> T.transition
        Tool.Text -> if (clip?.overlay?.kind == 0) T.text else T.textOrShape
        null -> T.edit
    }
    // No scrim: the picture and the timeline stay visible while adjusting.
    Sheet(studio.panel is Panel.ClipTool && clip != null, { studio.panel = Panel.None }, title.t, maxHeightFraction = 0.52f, scrim = false,
        actions = { IconBtn(Lucide.X, { studio.panel = Panel.None }, tint = pal.dim) },
    ) {
        if (clip == null || tool == null) return@Sheet
        DisposableEffect(clip.id) { onDispose { studio.endGesture() } }
        val g = studio::beginGesture
        val e = studio::endGesture
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (tool) {
                Tool.Fade -> FadeTool(studio, clip)
                Tool.Gain -> {
                    SliderRow(
                        T.gain.t, fmtDb(clip.gainDb), (clip.gainDb + 24) / 48f,
                        { v -> studio.editClips { if (it.isAudio) it.copy(gainDb = ((v * 48 - 24) * 2).roundToInt() / 2f) else it } },
                        center = true, onStart = g, onEnd = e,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn(T.reset.t, { studio.editClips { if (it.isAudio) it.copy(gainDb = 0f) else it } }, Modifier.weight(1f))
                        Btn(T.normalize.t, { studio.normalizeSelected() }, Modifier.weight(1f), icon = Lucide.Activity)
                    }
                }
                Tool.Stretch, Tool.Pitch -> StretchTool(studio, clip)
                Tool.Color -> ColorTool(studio, clip.fx)
                Tool.Look -> LookTool(studio, clip.fx)
                Tool.Transform -> TransformTool(studio, clip.fx)
                Tool.Transition -> TransitionTool(studio, clip.fx)
                Tool.Text -> clip.overlay?.let { OverlayTool(studio, it) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun FadeTool(studio: StudioState, clip: Clip) {
    val maxFrames = minOf(clip.frames, SAMPLE_RATE * 10L).coerceAtLeast(1)
    fun text(frames: Long) = "${fmt(frames.toFloat() / SAMPLE_RATE, 2)} s"
    SliderRow(
        T.fadeIn.t, text(clip.fadeIn), clip.fadeIn.toFloat() / maxFrames,
        { v -> studio.editClips { if (it.isAudio) it.copy(fadeIn = (v * maxFrames).toLong().coerceAtMost(it.frames)) else it } },
        onStart = studio::beginGesture, onEnd = studio::endGesture,
    )
    SliderRow(
        T.fadeOut.t, text(clip.fadeOut), clip.fadeOut.toFloat() / maxFrames,
        { v -> studio.editClips { if (it.isAudio) it.copy(fadeOut = (v * maxFrames).toLong().coerceAtMost(it.frames)) else it } },
        onStart = studio::beginGesture, onEnd = studio::endGesture,
    )
}

@Composable
private fun StretchTool(studio: StudioState, clip: Clip) {
    // Audio is rendered when applying, so the sliders work on a draft.
    var speed by remember(clip.id, clip.speed) { mutableFloatStateOf(clip.speed) }
    var pitch by remember(clip.id, clip.pitch) { mutableFloatStateOf(clip.pitch) }
    SliderRow(T.speed.t, "${fmt(speed, 2)}×", (log2(speed) + 1) / 2, { v -> speed = (2f.pow(v * 2 - 1) * 100).roundToInt() / 100f }, center = true)
    SliderRow(T.pitch.t, "${if (pitch > 0) "+" else ""}${pitch.roundToInt()} st", (pitch + 12) / 24f, { v -> pitch = (v * 24 - 12).roundToInt().toFloat() }, center = true)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(T.reset.t, { speed = 1f; pitch = 0f }, Modifier.weight(1f))
        Btn(T.apply.t, {
            studio.panel = Panel.None
            studio.stretchSelected(speed, pitch)
        }, Modifier.weight(1f), primary = true, enabled = speed != clip.speed || pitch != clip.pitch)
    }
}

private fun StudioState.editFx(f: (VideoFx) -> VideoFx) = editClips { if (it.video != null) it.copy(fx = f(it.fx)) else it }

private fun percent(v: Float) = "${(v * 100).roundToInt()} %"

@Composable
private fun ColorTool(studio: StudioState, fx: VideoFx) {
    val g = studio::beginGesture
    val e = studio::endGesture
    fun step(v: Float) = ((v * 2 - 1) * 50).roundToInt() / 50f
    SliderRow(T.brightness.t, percent(fx.brightness), (fx.brightness + 1) / 2, { v -> studio.editFx { it.copy(brightness = step(v)) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.contrast.t, percent(fx.contrast), (fx.contrast + 1) / 2, { v -> studio.editFx { it.copy(contrast = step(v)) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.saturation.t, percent(fx.saturation), (fx.saturation + 1) / 2, { v -> studio.editFx { it.copy(saturation = step(v)) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.temperature.t, percent(fx.temperature), (fx.temperature + 1) / 2, { v -> studio.editFx { it.copy(temperature = step(v)) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.hue.t, "${fx.hue.roundToInt()}°", (fx.hue + 180) / 360, { v -> studio.editFx { it.copy(hue = (v * 360 - 180).roundToInt().toFloat()) } }, center = true, onStart = g, onEnd = e)
    Spacer(Modifier.height(8.dp))
    Btn(T.reset.t, { studio.editFx { it.copy(brightness = 0f, contrast = 0f, saturation = 0f, temperature = 0f, hue = 0f) } }, Modifier.fillMaxWidth())
}

@Composable
private fun LookTool(studio: StudioState, fx: VideoFx) {
    Label(T.filter.t)
    Spacer(Modifier.height(6.dp))
    Choices(T.filterNames.map { it.t }, fx.filter, 3) { i -> studio.editFx { it.copy(filter = i) } }
    Spacer(Modifier.height(6.dp))
    SliderRow(T.blur.t, percent(fx.blur), fx.blur, { v -> studio.editFx { it.copy(blur = (v * 50).roundToInt() / 50f) } }, onStart = studio::beginGesture, onEnd = studio::endGesture)
    SliderRow(T.opacity.t, percent(fx.opacity), fx.opacity, { v -> studio.editFx { it.copy(opacity = (v * 50).roundToInt() / 50f) } }, onStart = studio::beginGesture, onEnd = studio::endGesture)
}

@Composable
private fun TransformTool(studio: StudioState, fx: VideoFx) {
    val g = studio::beginGesture
    val e = studio::endGesture
    Txt(T.previewHint.t, style = type.small, color = pal.dim, maxLines = 2)
    SliderRow(T.scale.t, percent(fx.scale), (fx.scale - 0.2f) / 2.8f, { v -> studio.editFx { it.copy(scale = ((0.2f + v * 2.8f) * 50).roundToInt() / 50f) } }, onStart = g, onEnd = e)
    SliderRow(T.posX.t, percent(fx.x), (fx.x + 1) / 2, { v -> studio.editFx { it.copy(x = ((v * 2 - 1) * 100).roundToInt() / 100f) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.posY.t, percent(fx.y), (fx.y + 1) / 2, { v -> studio.editFx { it.copy(y = ((v * 2 - 1) * 100).roundToInt() / 100f) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.rotation.t, "${fx.rotation.roundToInt()}°", (fx.rotation + 180) / 360, { v -> studio.editFx { it.copy(rotation = (v * 360 - 180).roundToInt().toFloat()) } }, center = true, onStart = g, onEnd = e)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(T.flip.t, { studio.editFx { it.copy(flip = !it.flip) } }, Modifier.weight(1f), primary = fx.flip, icon = Lucide.FlipHorizontal)
        Btn(T.reset.t, { studio.editFx { it.copy(scale = 1f, x = 0f, y = 0f, rotation = 0f, flip = false) } }, Modifier.weight(1f))
    }
}

@Composable
private fun TransitionTool(studio: StudioState, fx: VideoFx) {
    val names = T.transitionNames.map { it.t }
    fun ms(v: Float) = (100 + v * 2900).roundToInt() / 50 * 50
    Label(T.transIn.t)
    Spacer(Modifier.height(6.dp))
    Choices(names, fx.transIn, 4) { i -> studio.editFx { it.copy(transIn = i) } }
    SliderRow(T.duration.t, "${fmt(fx.transInMs / 1000f, 2)} s", (fx.transInMs - 100) / 2900f, { v -> studio.editFx { it.copy(transInMs = ms(v)) } }, onStart = studio::beginGesture, onEnd = studio::endGesture)
    Label(T.transOut.t)
    Spacer(Modifier.height(6.dp))
    Choices(names, fx.transOut, 4) { i -> studio.editFx { it.copy(transOut = i) } }
    SliderRow(T.duration.t, "${fmt(fx.transOutMs / 1000f, 2)} s", (fx.transOutMs - 100) / 2900f, { v -> studio.editFx { it.copy(transOutMs = ms(v)) } }, onStart = studio::beginGesture, onEnd = studio::endGesture)
}

@Composable
private fun OverlayTool(studio: StudioState, o: Overlay) {
    val g = studio::beginGesture
    val e = studio::endGesture
    fun set(f: (Overlay) -> Overlay) = studio.editClips { c -> c.overlay?.let { c.copy(overlay = f(it)) } ?: c }
    if (o.kind == 0) {
        TextInput(o.text, { text ->
            studio.beginGesture()
            studio.editClips { c -> c.overlay?.let { c.copy(overlay = it.copy(text = text.take(80)), name = text.take(24)) } ?: c }
        }, Modifier.fillMaxWidth(), T.text.t)
        Spacer(Modifier.height(8.dp))
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        OVERLAY_COLORS.forEachIndexed { i, argb ->
            Box(
                Modifier.size(30.dp).pressable { set { it.copy(color = i) } }.background(Color(argb), CircleShape)
                    .border(if (o.color == i) 2.dp else 1.dp, if (o.color == i) pal.accent else pal.line, CircleShape),
            )
        }
    }
    SliderRow(T.scale.t, percent(o.size), (o.size - 0.02f) / 0.78f, { v -> set { it.copy(size = ((0.02f + v * 0.78f) * 200).roundToInt() / 200f) } }, onStart = g, onEnd = e)
    if (o.kind != 0) SliderRow(T.width.t, "${fmt(o.aspect, 1)}×", (log2(o.aspect) + 3) / 6, { v -> set { it.copy(aspect = (2f.pow(v * 6 - 3) * 10).roundToInt() / 10f) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.rotation.t, "${o.rotation.roundToInt()}°", (o.rotation + 180) / 360, { v -> set { it.copy(rotation = (v * 360 - 180).roundToInt().toFloat()) } }, center = true, onStart = g, onEnd = e)
    SliderRow(T.opacity.t, percent(o.opacity), o.opacity, { v -> set { it.copy(opacity = (v * 50).roundToInt() / 50f) } }, onStart = g, onEnd = e)
    if (o.kind == 0) {
        Spacer(Modifier.height(8.dp))
        Btn(T.plate.t, { set { it.copy(box = !it.box) } }, Modifier.fillMaxWidth(), primary = o.box)
    }
    Txt(T.previewHint.t, Modifier.padding(top = 8.dp), type.small, pal.dim, maxLines = 2)
}

@Composable
fun BoxScope.AddOverlaySheet(studio: StudioState) {
    val lang = LocalLang.current
    Sheet(studio.panel == Panel.AddOverlay, { studio.panel = Panel.None }, T.textOrShape.t) {
        ListRow(T.text.t, { studio.addOverlay(0, T.sampleText.of(lang)) }, icon = Lucide.Type)
        ListRow(T.rectangle.t, { studio.addOverlay(1, "") }, icon = Lucide.Square)
        ListRow(T.ellipse.t, { studio.addOverlay(2, "") }, icon = Lucide.Circle)
    }
}

/** Half width and half height of an overlay in a frame of the given size. Text is estimated. */
fun overlayExtent(o: Overlay, frame: Size): Offset {
    val hh = o.size * frame.height / 2
    return Offset(if (o.kind == 0) maxOf(o.text.length * o.size * frame.height * 0.24f, hh) else hh * o.aspect, hh)
}

/**
 * Draws one overlay into a frame that fills the draw scope. The export draws the same
 * geometry with Android's canvas (VideoSupport), so keep the two in step.
 */
fun DrawScope.drawOverlay(o: Overlay, measurer: TextMeasurer, style: TextStyle, selected: Boolean) {
    val centre = Offset(size.width / 2 + o.x * size.width, size.height / 2 + o.y * size.height)
    val color = Color(OVERLAY_COLORS[o.color.coerceIn(0, OVERLAY_COLORS.lastIndex)]).copy(alpha = o.opacity.coerceIn(0f, 1f))
    rotate(o.rotation, centre) {
        var half = overlayExtent(o, size)
        if (o.kind == 0) {
            val px = o.size * size.height * 0.8f
            val layout = measurer.measure(o.text, style.copy(fontSize = px.toSp(), color = color), maxLines = 1)
            half = Offset(layout.size.width / 2f, layout.size.height / 2f)
            if (o.box) {
                val pad = px * 0.25f
                half += Offset(pad, pad * 0.4f)
                drawRoundRect(Color.Black.copy(alpha = 0.55f * o.opacity), centre - half, Size(half.x * 2, half.y * 2), CornerRadius(px * 0.2f))
            }
            drawText(layout, topLeft = centre - Offset(layout.size.width / 2f, layout.size.height / 2f))
        } else if (o.kind == 1) {
            drawRect(color, centre - half, Size(half.x * 2, half.y * 2))
        } else {
            drawOval(color, centre - half, Size(half.x * 2, half.y * 2))
        }
        if (selected) {
            val m = 4.dp.toPx()
            drawRoundRect(
                Color.White, centre - half - Offset(m, m), Size(half.x * 2 + 2 * m, half.y * 2 + 2 * m), CornerRadius(m),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}
