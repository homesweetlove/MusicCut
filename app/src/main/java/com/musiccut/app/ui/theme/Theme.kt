package com.musiccut.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Palette modelled on Logic Pro's dark graphite interface. */
object DawColors {
    // window chrome
    val background = Color(0xFF1B1B1D)
    val surface = Color(0xFF262628)
    val surfaceHigh = Color(0xFF333336)
    val panel = Color(0xFF2B2B2E)
    val panelBorder = Color(0xFF111113)
    val hairline = Color(0xFF3C3C40)
    val controlBarTop = Color(0xFF3A3A3D)
    val controlBarBottom = Color(0xFF2A2A2D)
    val buttonTop = Color(0xFF4A4A4E)
    val buttonBottom = Color(0xFF38383B)

    // LCD
    val lcdBackground = Color(0xFF0D0E10)
    val lcdText = Color(0xFFE9ECF2)
    val lcdDim = Color(0xFF7D828C)
    val lcdLabel = Color(0xFF5E636C)

    // tracks area
    val laneEven = Color(0xFF28282B)
    val laneOdd = Color(0xFF28282B)
    val laneSelected = Color(0xFF333640)
    val laneSeparator = Color(0xFF1A1A1C)
    val gridBar = Color(0xFF3E3E43)
    val gridBeat = Color(0xFF303034)
    val rulerBackground = Color(0xFF2F2F32)

    // accents
    val playhead = Color(0xFFFFFFFF)
    val cycle = Color(0xFFF5C400)
    val range = Color(0x553D8BFF)
    val accent = Color(0xFF3D8BFF)
    val record = Color(0xFFFF3B30)
    val mute = Color(0xFF3D8BFF)
    val solo = Color(0xFFF5C400)
    val input = Color(0xFFFF9500)
    val text = Color(0xFFE6E6E8)
    val textDim = Color(0xFF9A9AA0)
    val meterGreen = Color(0xFF30D158)
    val meterYellow = Color(0xFFFFD60A)
    val meterRed = Color(0xFFFF453A)
    val knobBody = Color(0xFF3A3A3E)
    val knobTrack = Color(0xFF151517)
    val faderCap = Color(0xFFC9C9CE)

    /** Logic-like region / track colors. */
    val trackColors = listOf(
        Color(0xFF2F7ED8), // blue (Logic default for audio)
        Color(0xFF34A853), // green
        Color(0xFFE8A62A), // yellow/orange
        Color(0xFFD8453B), // red
        Color(0xFF9A5BD6), // purple
        Color(0xFF27AFC4), // cyan
        Color(0xFFD2549D), // pink
        Color(0xFF8E9C4A), // olive
    )

    fun track(index: Int) = trackColors[((index % trackColors.size) + trackColors.size) % trackColors.size]

    fun darker(c: Color, f: Float = 0.35f) = lerp(c, Color.Black, f)
    fun lighter(c: Color, f: Float = 0.3f) = lerp(c, Color.White, f)

    val controlBarBrush get() = Brush.verticalGradient(listOf(controlBarTop, controlBarBottom))
    val buttonBrush get() = Brush.verticalGradient(listOf(buttonTop, buttonBottom))
}

private val scheme = darkColorScheme(
    primary = DawColors.accent,
    onPrimary = Color.White,
    secondary = DawColors.cycle,
    background = DawColors.background,
    onBackground = DawColors.text,
    surface = DawColors.surface,
    onSurface = DawColors.text,
    surfaceVariant = DawColors.surfaceHigh,
    onSurfaceVariant = DawColors.textDim,
    surfaceContainer = DawColors.panel,
    surfaceContainerHigh = DawColors.surfaceHigh,
    surfaceContainerLow = DawColors.surface,
    outline = DawColors.hairline,
    error = DawColors.record,
)

private val typography = Typography(
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.sp),
    labelSmall = TextStyle(fontSize = 10.sp),
)

@Composable
fun MusicCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
