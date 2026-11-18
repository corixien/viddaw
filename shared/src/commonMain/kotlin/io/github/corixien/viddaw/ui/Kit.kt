package io.github.corixien.viddaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small component set drawn on Compose foundation, so the app owns its look on every platform. */

val pal @Composable get() = LocalPalette.current
val type @Composable get() = LocalType.current

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = type.body,
    color: Color = pal.text,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    BasicText(
        text, modifier,
        style.copy(color = color, textAlign = align ?: TextAlign.Unspecified),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Click target that answers a press by shrinking slightly, instead of a ripple. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMedium))
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Icon(icon: ImageVector, tint: Color = pal.text, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(icon, null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    activeColor: Color? = null,
    size: Dp = 40.dp,
) {
    val p = pal
    val fill by animateColorAsState(if (active) activeColor ?: p.accent else Color.Transparent)
    val ink = when {
        !enabled -> p.dim.copy(alpha = 0.4f)
        active -> if (activeColor != null) Color.White else p.onAccent
        else -> tint ?: p.text
    }
    Box(
        modifier.size(size).pressable(enabled, onClick).background(fill, RoundedCornerShape(Radius.control)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, ink, size * 0.5f) }
}

@Composable
fun Btn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val p = pal
    val bg = if (primary) p.accent else p.raised
    val ink = if (danger) p.record else if (primary) p.onAccent else p.text
    Row(
        modifier.height(40.dp).pressable(enabled, onClick)
            .background(bg.copy(alpha = if (enabled) 1f else 0.5f), RoundedCornerShape(Radius.control))
            .then(if (primary) Modifier else Modifier.border(1.dp, p.line, RoundedCornerShape(Radius.control)))
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, ink, 18.dp)
        Txt(label, style = type.label, color = ink)
    }
}

/** The M / S style latch buttons on track headers and mixer strips. */
@Composable
fun Latch(label: String, on: Boolean, onColor: Color, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val p = pal
    val bg by animateColorAsState(if (on) onColor else p.raised)
    Box(
        modifier.size(size).pressable(onClick = onToggle).background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, if (on) Color.Transparent else p.line, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) { Txt(label, style = type.small, color = if (on) Color.White else p.dim) }
}

/** macOS-style segmented control; the selection slides between segments. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = pal
    BoxWithConstraints(modifier.height(34.dp).background(p.sunken, RoundedCornerShape(Radius.control)).padding(2.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected.coerceIn(0, options.lastIndex), spring(stiffness = Spring.StiffnessMediumLow))
        Box(Modifier.offset(x = x).width(w).height(30.dp).background(p.surface, RoundedCornerShape(6.dp)))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).height(30.dp).clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Txt(label, style = type.label, color = if (i == selected) p.text else p.dim) }
            }
        }
    }
}

/**
 * Horizontal slider over 0..1. [onStart]/[onEnd] bracket a drag so it becomes one undo step.
 * A double tap jumps to [reset]: the middle for centred sliders, nothing unless given otherwise.
 */
@Composable
fun HSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
    center: Boolean = false,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    reset: Float? = if (center) 0.5f else null,
) {
    val p = pal
    val fill = color ?: p.accent
    val change by rememberUpdatedState(onChange)
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    val home by rememberUpdatedState(reset)
    Canvas(
        modifier.height(32.dp).pointerInput(Unit) {
            var lastTap = 0L
            awaitEachGesture {
                val down = awaitFirstDown()
                val pad = 12.dp.toPx()
                fun at(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                val double = down.uptimeMillis - lastTap < 320
                lastTap = 0
                start()
                var moved = 0f
                val target = home
                if (double && target != null) change(target) else change(at(down.position.x))
                while (true) {
                    val e = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!e.pressed) break
                    if (e.positionChange() != Offset.Zero) {
                        moved += e.positionChange().getDistance()
                        if (!(double && target != null)) change(at(e.position.x))
                    }
                    e.consume()
                }
                if (moved < viewConfiguration.touchSlop) lastTap = down.uptimeMillis
                end()
            }
        },
    ) {
        val pad = 12.dp.toPx()
        val y = size.height / 2
        val track = 4.dp.toPx()
        val w = size.width - 2 * pad
        val x = pad + w * value.coerceIn(0f, 1f)
        drawRoundRect(p.sunken, Offset(pad, y - track / 2), Size(w, track), CornerRadius(track))
        val from = if (center) pad + w / 2 else pad
        drawRoundRect(fill, Offset(minOf(from, x), y - track / 2), Size(kotlin.math.abs(x - from), track), CornerRadius(track))
        drawCircle(p.line, 10.dp.toPx(), Offset(x, y))
        drawCircle(if (p.dark) Color(0xFFE5E5EA) else Color.White, 9.dp.toPx(), Offset(x, y))
    }
}

/** Label, value and slider on one row. */
@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
    center: Boolean = false,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    reset: Float? = if (center) 0.5f else null,
) {
    Row(modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) Txt(label, Modifier.width(92.dp), type.label, pal.dim)
        HSlider(value, onChange, Modifier.weight(1f), color, center, onStart, onEnd, reset)
        Txt(valueText, Modifier.width(72.dp), type.label.copy(fontFeatureSettings = "tnum"), align = TextAlign.End)
    }
}

@Composable
fun TextInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", number: Boolean = false, autoFocus: Boolean = false, onDone: () -> Unit = {}) {
    val p = pal
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier.height(42.dp).background(p.sunken, RoundedCornerShape(Radius.control)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Txt(placeholder, color = p.dim)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = type.body.copy(color = p.text), singleLine = true, cursorBrush = SolidColor(p.text),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (number) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    danger: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).pressable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(36.dp).background(pal.sunken, RoundedCornerShape(Radius.control)), contentAlignment = Alignment.Center) {
                Icon(icon, iconTint ?: if (danger) pal.record else pal.text, 18.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Txt(title, style = type.label.copy(fontSize = 15.sp), color = if (danger) pal.record else pal.text)
            if (subtitle != null) Txt(subtitle, style = type.small, color = pal.dim)
        }
        trailing()
    }
}

/**
 * Bottom sheet inside the app window. It slides up over a scrim; the caller owns
 * [visible], so the system back action can close it through app state.
 */
@Composable
fun BoxScope.Sheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    maxHeightFraction: Float = 0.86f,
    scrim: Boolean = true,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = pal
    val scope = rememberCoroutineScope()
    // Dragging the handle or title down pulls the sheet with the finger and closes it past a threshold.
    val pull = remember { Animatable(0f) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible) { if (visible) pull.snapTo(0f) }
    // Without a scrim the screen behind stays usable; the sheet then closes by its handle or button.
    if (scrim) AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize().background(p.scrim).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
    }
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it },
        exit = slideOutVertically(tween(180)) { it },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight * maxHeightFraction)
                    .offset { IntOffset(0, pull.value.roundToInt()) }
                    .then(if (scrim) Modifier else Modifier.border(1.dp, p.line, RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)))
                    .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                    .background(p.surface)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                Column(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        val velocity = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { velocity.resetTracking() },
                            onDragEnd = {
                                if (pull.value > 72.dp.toPx() || velocity.calculateVelocity().y > 900f) dismiss()
                                else scope.launch { pull.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                            },
                            onDragCancel = { scope.launch { pull.animateTo(0f) } },
                        ) { change, dy ->
                            velocity.addPosition(change.uptimeMillis, change.position)
                            scope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) }
                        }
                    },
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(36.dp, 4.dp).background(p.line, CircleShape))
                    }
                    if (title != null) {
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(title, Modifier.weight(1f), type.heading)
                            actions()
                        }
                    } else {
                        Spacer(Modifier.height(14.dp))
                    }
                }
                Column(Modifier.padding(padding), content = content)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Peak meter bar. [level] is linear 0..1+. */
@Composable
fun MeterBar(level: () -> Float, modifier: Modifier = Modifier, vertical: Boolean = true) {
    val p = pal
    Canvas(modifier) {
        val v = level()
        // Map -48 dB..0 dB to the bar so quiet signals still register.
        val db = if (v <= 0.004f) -48f else (20f * kotlin.math.log10(v)).coerceIn(-48f, 3f)
        val f = ((db + 48f) / 48f).coerceIn(0f, 1f)
        val r = CornerRadius(2.dp.toPx())
        drawRoundRect(p.sunken, cornerRadius = r)
        val c = if (db > -0.5f) p.record else if (db > -9f) Color(0xFFD9A441) else Color(0xFF53B37F)
        if (vertical) drawRoundRect(c, Offset(0f, size.height * (1 - f)), Size(size.width, size.height * f), r)
        else drawRoundRect(c, Offset.Zero, Size(size.width * f, size.height), r)
    }
}
