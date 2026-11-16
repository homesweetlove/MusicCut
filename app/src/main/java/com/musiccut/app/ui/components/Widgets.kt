package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.theme.DawColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Logic-style bevelled push button (text or icon), lit with [activeColor] when [active]. */
@Composable
fun LogicButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    active: Boolean = false,
    activeColor: Color = DawColors.accent,
    enabled: Boolean = true,
    width: Dp = 34.dp,
    height: Dp = 28.dp,
    contentDescription: String? = text,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    val bg: Brush = if (active) Brush.verticalGradient(listOf(DawColors.lighter(activeColor, 0.1f), activeColor)) else DawColors.buttonBrush
    val fg = when {
        !enabled -> DawColors.textDim.copy(alpha = 0.35f)
        active && (activeColor == DawColors.solo || activeColor == DawColors.cycle) -> Color.Black
        active -> Color.White
        else -> DawColors.text
    }
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(bg)
            .border(0.5.dp, DawColors.panelBorder, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(minOf(height, width) * 0.66f))
        else if (text != null) Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Small square M / S / R style toggle used on track headers and channel strips. */
@Composable
fun ToggleChip(label: String, on: Boolean, onColor: Color, width: Dp = 22.dp, height: Dp = 18.dp, onClick: () -> Unit) {
    LogicButton(text = label, active = on, activeColor = onColor, width = width, height = height, onClick = onClick)
}

/**
 * Rotary knob (Logic plugin style). [value] is normalized 0..1. Drag vertically to change,
 * double-tap to reset to [default]. [bipolar] draws the value arc from 12 o'clock.
 */
@Composable
fun RotaryKnob(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    default: Float = 0f,
    bipolar: Boolean = false,
    diameter: Dp = 52.dp,
    color: Color = DawColors.accent,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(modifier.width(diameter + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Text(label, fontSize = 10.sp, color = DawColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Canvas(
            Modifier
                .size(diameter)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(default) }) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { c, dy ->
                        c.consume()
                        change((current - dy / (diameter.toPx() * 3.5f)).coerceIn(0f, 1f))
                    }
                }
        ) {
            val r = this.size.minDimension / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val stroke = r * 0.16f
            val arcR = r - stroke / 2f
            val arcTopLeft = Offset(center.x - arcR, center.y - arcR)
            val arcSize = Size(arcR * 2, arcR * 2)
            // track: 270° sweep from 135°
            drawArc(DawColors.knobTrack, 135f, 270f, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val v = value.coerceIn(0f, 1f)
            if (bipolar) {
                val startA = 270f
                val sweep = (v - 0.5f) * 270f
                drawArc(color, startA, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            } else {
                drawArc(color, 135f, v * 270f, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // body
            val bodyR = r * 0.68f
            drawCircle(
                Brush.verticalGradient(listOf(Color(0xFF55555A), DawColors.knobBody, Color(0xFF242427)), startY = center.y - bodyR, endY = center.y + bodyR),
                bodyR, center,
            )
            drawCircle(Color.Black.copy(alpha = 0.6f), bodyR, center, style = Stroke(1.5f))
            // pointer
            val angle = (135f + v * 270f) * PI.toFloat() / 180f
            val p1 = Offset(center.x + cos(angle) * bodyR * 0.25f, center.y + sin(angle) * bodyR * 0.25f)
            val p2 = Offset(center.x + cos(angle) * bodyR * 0.9f, center.y + sin(angle) * bodyR * 0.9f)
            drawLine(Color.White, p1, p2, strokeWidth = r * 0.08f, cap = StrokeCap.Round)
        }
        if (valueText != null) {
            Box(
                Modifier.padding(top = 2.dp).clip(RoundedCornerShape(3.dp)).background(DawColors.lcdBackground).padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(valueText, fontSize = 10.sp, color = DawColors.lcdText, maxLines = 1)
            }
        }
    }
}

/** Plugin window power (bypass) button. */
@Composable
fun PowerButton(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (on) DawColors.accent else DawColors.surfaceHigh)
            .border(0.5.dp, DawColors.panelBorder, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PowerSettingsNew, "On/Off", tint = if (on) Color.White else DawColors.textDim, modifier = Modifier.size(18.dp))
    }
}

/** Text button with a ▾ that opens a dropdown (Logic's local "Edit / Functions / View" menus). */
@Composable
fun MenuButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = DawColors.text,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 12.sp, color = color, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null, tint = DawColors.textDim, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

/** Disclosure section used in the inspector ("▸ Region: Verse"). */
@Composable
fun Disclosure(
    title: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(DawColors.surfaceHigh).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null, tint = DawColors.textDim, modifier = Modifier.size(16.dp),
            )
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

/** Label / value row in the inspector. */
@Composable
fun InspectorRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = DawColors.textDim, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, color = if (onClick != null) DawColors.accent else DawColors.text)
    }
}

/** Horizontal level meter with peak colouring. */
@Composable
fun HorizontalMeter(level: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.height(4.dp)) {
        drawRect(DawColors.lcdBackground)
        val db = if (level <= 0f) -60f else (20f * kotlin.math.log10(level)).coerceIn(-60f, 6f)
        val f = ((db + 60f) / 66f).coerceIn(0f, 1f)
        drawRect(
            Brush.horizontalGradient(0f to DawColors.meterGreen, 0.75f to DawColors.meterYellow, 0.92f to DawColors.meterRed, endX = size.width),
            size = Size(size.width * f, size.height),
        )
    }
}
