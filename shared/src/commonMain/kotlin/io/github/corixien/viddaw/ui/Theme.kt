package io.github.corixien.viddaw.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Flat graphite palette. Chrome stays neutral; the only saturated colour on screen
 * belongs to tracks and their clips, plus red for recording.
 */
class Palette(
    val window: Color,
    val surface: Color,
    val raised: Color,
    val sunken: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
    val accent: Color,
    val onAccent: Color,
    val record: Color,
    val scrim: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    window = Color(0xFFECECEE), surface = Color(0xFFFFFFFF), raised = Color(0xFFF6F6F7), sunken = Color(0xFFDFDFE3),
    line = Color(0x1F000000), text = Color(0xFF1D1D1F), dim = Color(0xFF6E6E73),
    accent = Color(0xFF3A3A3C), onAccent = Color(0xFFFFFFFF), record = Color(0xFFE5484D),
    scrim = Color(0x52000000), dark = false,
)

val DarkPalette = Palette(
    window = Color(0xFF1C1C1E), surface = Color(0xFF2C2C2E), raised = Color(0xFF3A3A3C), sunken = Color(0xFF131314),
    line = Color(0x24FFFFFF), text = Color(0xFFF2F2F7), dim = Color(0xFF98989D),
    accent = Color(0xFFE5E5EA), onAccent = Color(0xFF1C1C1E), record = Color(0xFFFF5A5F),
    scrim = Color(0x7A000000), dark = true,
)

/** Track colours, index stored in the project. Tuned to sit on both palettes. */
val TrackColors = listOf(
    Color(0xFF4A90E2), Color(0xFFE5704B), Color(0xFF53B37F), Color(0xFFD9A441),
    Color(0xFF9C6ADE), Color(0xFFDB5C8E), Color(0xFF3FB8C4), Color(0xFF8E9AAF),
)

fun trackColor(index: Int): Color = TrackColors[((index % TrackColors.size) + TrackColors.size) % TrackColors.size]

class Type(family: FontFamily) {
    private val base = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
    val title = base.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val heading = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    val body = base.copy(fontSize = 14.sp)
    val label = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val small = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val digits = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
}

object Radius {
    val control = 8.dp
    val panel = 12.dp
    val sheet = 18.dp
}

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalType = staticCompositionLocalOf { Type(FontFamily.Default) }

@Composable
fun ViddawTheme(dark: Boolean, font: FontFamily, lang: Lang, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPalette provides if (dark) DarkPalette else LightPalette,
        LocalType provides Type(font),
        LocalLang provides lang,
        content = content,
    )
}
