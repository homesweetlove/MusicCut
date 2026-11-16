package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginAnalysis
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.PluginSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Logic-style plugin window: header (power, preset browser, title) + the plugin's own interface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginWindow(vm: DawViewModel, ownerId: String, slotId: String, onDismiss: () -> Unit) {
    val slot: PluginSlot? = vm.pluginsOf(ownerId).firstOrNull { it.id == slotId }
    val d = slot?.let { PluginRegistry.find(it.pluginId) }
    if (slot == null || d == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DawColors.panel,
        dragHandle = null,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PluginHeader(vm, ownerId, slot, d, onDismiss)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (d.id == "compressor" || d.id == "noise_gate") SidechainSelector(vm, ownerId, slot)
                when {
                    d.id == "channel_eq" -> ChannelEqEditor(vm, ownerId, slot, d)
                    d.id == "graphic_eq" || d.id == "match_eq" -> GraphicEqEditor(vm, ownerId, slot, d)
                    d.id in METER_IDS -> {
                        MeterReadouts(vm, slot, d.id)
                        KnobPanel(d.params, slot.params) { id, v -> vm.setPluginParam(ownerId, slot.id, id, v) }
                    }
                    d.id in DYNAMICS_IDS || d.id == "enveloper" -> DynamicsEditor(vm, ownerId, slot, d)
                    else -> KnobPanel(d.params, slot.params) { id, v -> vm.setPluginParam(ownerId, slot.id, id, v) }
                }
            }
            Text(
                "${d.category.label} · ${d.description}",
                fontSize = 10.sp, color = DawColors.textDim,
                modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun PluginHeader(vm: DawViewModel, ownerId: String, slot: PluginSlot, d: PluginDescriptor, onDismiss: () -> Unit) {
    val presetNames = listOf("Default") + d.presets.keys
    val currentPreset = remember(slot.params) {
        val defaults = d.defaults()
        presetNames.firstOrNull { name ->
            val values = if (name == "Default") defaults else defaults + d.presets.getValue(name)
            values.all { (k, v) -> abs((slot.params[k] ?: v) - v) < 1e-4f }
        } ?: "Manual"
    }
    fun applyIndex(i: Int) {
        val name = presetNames[(i + presetNames.size) % presetNames.size]
        vm.applyPreset(ownerId, slot.id, if (name == "Default") emptyMap() else d.presets.getValue(name))
    }
    Row(
        Modifier.fillMaxWidth().background(DawColors.controlBarBrush).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PowerButton(on = !slot.bypassed) { vm.setPluginBypass(ownerId, slot.id, !slot.bypassed) }
        LogicButton(icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 프리셋", width = 26.dp) {
            applyIndex(presetNames.indexOf(currentPreset).coerceAtLeast(0) - 1)
        }
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(DawColors.lcdBackground)) {
            MenuButton(currentPreset, color = DawColors.lcdText) { close ->
                presetNames.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { applyIndex(i); close() })
                }
            }
        }
        LogicButton(icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 프리셋", width = 26.dp) {
            applyIndex(presetNames.indexOf(currentPreset) + 1)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DawColors.text, maxLines = 1)
            Text(vm.ownerName(ownerId), fontSize = 10.sp, color = DawColors.textDim, maxLines = 1)
        }
        LogicButton(icon = Icons.Filled.Delete, contentDescription = "플러그인 삭제") { vm.removePlugin(ownerId, slot.id); onDismiss() }
        LogicButton(icon = Icons.Filled.Close, contentDescription = "닫기", onClick = onDismiss)
    }
}

/** Knobs for continuous parameters, segmented buttons for switches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnobPanel(params: List<ParamSpec>, values: Map<String, Float>, onChange: (String, Float) -> Unit) {
    val discrete = params.filter { it.isDiscrete }
    val continuous = params.filterNot { it.isDiscrete }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        discrete.forEach { spec ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, fontSize = 11.sp, color = DawColors.textDim, modifier = Modifier.width(96.dp))
                Segmented(spec.choices!!, (values[spec.id] ?: spec.default).toInt()) { onChange(spec.id, it.toFloat()) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            continuous.forEach { spec ->
                val v = values[spec.id] ?: spec.default
                RotaryKnob(
                    value = spec.toNormalized(v),
                    onChange = { onChange(spec.id, spec.fromNormalized(it)) },
                    label = spec.name,
                    valueText = spec.format(v),
                    default = spec.toNormalized(spec.default),
                    bipolar = spec.min < 0f && spec.max > 0f,
                    diameter = 48.dp,
                )
            }
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(5.dp)).border(0.5.dp, DawColors.panelBorder, RoundedCornerShape(5.dp)).horizontalScroll(rememberScrollState()),
    ) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier
                    .background(if (i == selected) DawColors.accent else DawColors.surfaceHigh)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(o, fontSize = 11.sp, color = if (i == selected) Color.White else DawColors.text)
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Channel EQ

private data class EqBand(
    val key: String,
    val label: String,
    val color: Color,
    val freqParam: String,
    val gainParam: String?,
    val qParam: String?,
    val onParam: String?,
)

private val EQ_BANDS = listOf(
    EqBand("hp", "Low Cut", Color(0xFFE5484D), "hpFreq", null, null, "hpOn"),
    EqBand("ls", "Low Shelf", Color(0xFFF76B15), "lsFreq", "lsGain", null, null),
    EqBand("b1", "Band 1", Color(0xFFFFC53D), "b1Freq", "b1Gain", "b1Q", null),
    EqBand("b2", "Band 2", Color(0xFF46A758), "b2Freq", "b2Gain", "b2Q", null),
    EqBand("b3", "Band 3", Color(0xFF12A594), "b3Freq", "b3Gain", "b3Q", null),
    EqBand("b4", "Band 4", Color(0xFF3E63DD), "b4Freq", "b4Gain", "b4Q", null),
    EqBand("hs", "High Shelf", Color(0xFF8E4EC6), "hsFreq", "hsGain", null, null),
    EqBand("lp", "High Cut", Color(0xFFD6409F), "lpFreq", null, null, "lpOn"),
)

private const val EQ_RANGE_DB = 24f
private fun freqToX(f: Float, w: Float) = (ln(f / 20f) / ln(1000f)) * w
private fun xToFreq(x: Float, w: Float) = 20f * exp((x / w).coerceIn(0f, 1f) * ln(1000f))

/** Logic "Channel EQ": response curve with draggable bands over a live spectrum analyzer. */
@Composable
private fun ChannelEqEditor(vm: DawViewModel, ownerId: String, slot: PluginSlot, d: PluginDescriptor) {
    val params = d.defaults() + slot.params
    val response = remember(slot.params) { PluginAnalysis.frequencyResponse(d, params, points = 200) }
    var selected by remember { mutableStateOf("b2") }
    var spectrum by remember { mutableStateOf<FloatArray?>(null) }
    val playing by vm.playing.collectAsState()
    val measurer = rememberTextMeasurer()
    val localDensity = LocalDensity.current
    val paramsRef by rememberUpdatedState(params)

    LaunchedEffect(slot.id, playing) {
        val buf = FloatArray(4096)
        while (playing) {
            val tap = vm.pluginInstance(slot.id)?.analyzer
            if (tap != null) {
                tap.snapshot(buf)
                val fresh = withContext(Dispatchers.Default) { PluginAnalysis.spectrum(buf, vm.sampleRate, 160) }
                val prev = spectrum
                spectrum = if (prev == null) fresh else FloatArray(fresh.size) { max(fresh[it], prev[it] - 2.5f) }
            }
            delay(50)
        }
        spectrum = null
    }

    fun set(id: String, v: Float) = vm.setPluginParam(ownerId, slot.id, id, v)
    fun specOf(id: String) = d.params.first { it.id == id }
    fun bandPos(b: EqBand, w: Float, h: Float): Offset {
        val x = freqToX(paramsRef[b.freqParam] ?: 1000f, w)
        val g = b.gainParam?.let { paramsRef[it] } ?: 0f
        return Offset(x, h / 2 - g / EQ_RANGE_DB * (h / 2))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF15171C), Color(0xFF0C0D10))))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            EQ_BANDS.minByOrNull { (bandPos(it, w, h) - o).getDistance() }?.let { selected = it.key }
                        },
                        onDoubleTap = { o ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val b = EQ_BANDS.minByOrNull { (bandPos(it, w, h) - o).getDistance() } ?: return@detectTapGestures
                            selected = b.key
                            b.onParam?.let { set(it, if ((paramsRef[it] ?: 0f) > 0.5f) 0f else 1f) }
                            b.gainParam?.let { set(it, 0f) }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var band: EqBand? = null
                    detectDragGestures(
                        onDragStart = { o ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val nearest = EQ_BANDS.minByOrNull { (bandPos(it, w, h) - o).getDistance() }
                            band = nearest?.takeIf { (bandPos(it, w, h) - o).getDistance() < with(localDensity) { 48.dp.toPx() } }
                            band?.let { b ->
                                selected = b.key
                                b.onParam?.let { if ((paramsRef[it] ?: 0f) < 0.5f) set(it, 1f) }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val b = band ?: return@detectDragGestures
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val fs = specOf(b.freqParam)
                            set(b.freqParam, xToFreq(change.position.x, w).coerceIn(fs.min, fs.max))
                            b.gainParam?.let { gp ->
                                val g = ((h / 2 - change.position.y) / (h / 2) * EQ_RANGE_DB).coerceIn(-24f, 24f)
                                set(gp, g)
                            }
                        },
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            fun yOf(db: Float) = h / 2 - db / EQ_RANGE_DB * (h / 2)
            // analyzer (behind everything)
            spectrum?.let { sp ->
                val path = Path().apply { moveTo(0f, h) }
                sp.forEachIndexed { i, db ->
                    val x = i.toFloat() / (sp.size - 1) * w
                    val y = h - ((db + 96f) / 96f).coerceIn(0f, 1f) * h * 0.95f
                    path.lineTo(x, y)
                }
                path.lineTo(w, h); path.close()
                drawPath(path, Brush.verticalGradient(listOf(Color(0x5561B0FF), Color(0x1161B0FF))))
            }
            // grid
            for (f in listOf(30f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
                val x = freqToX(f, w)
                drawLine(DawColors.gridBeat, Offset(x, 0f), Offset(x, h))
                val label = if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}"
                drawText(measurer, label, Offset(x + 2f, h - 14f), TextStyle(color = DawColors.lcdLabel, fontSize = 8.sp))
            }
            for (db in listOf(-18f, -12f, -6f, 6f, 12f, 18f)) {
                drawLine(DawColors.gridBeat, Offset(0f, yOf(db)), Offset(w, yOf(db)))
                drawText(measurer, (if (db > 0) "+" else "") + db.toInt(), Offset(3f, yOf(db) - 11f), TextStyle(color = DawColors.lcdLabel, fontSize = 8.sp))
            }
            drawLine(DawColors.gridBar, Offset(0f, yOf(0f)), Offset(w, yOf(0f)))
            // curve
            val curve = Path()
            val fill = Path()
            response.forEachIndexed { i, db ->
                val x = i.toFloat() / (response.size - 1) * w
                val y = yOf(db.coerceIn(-EQ_RANGE_DB, EQ_RANGE_DB))
                if (i == 0) { curve.moveTo(x, y); fill.moveTo(x, yOf(0f)); fill.lineTo(x, y) } else { curve.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(w, yOf(0f)); fill.close()
            val active = !slot.bypassed
            drawPath(fill, (if (active) DawColors.accent else DawColors.textDim).copy(alpha = 0.18f))
            drawPath(curve, if (active) Color(0xFF9CC7FF) else DawColors.textDim, style = Stroke(2.5f))
            // band handles
            EQ_BANDS.forEachIndexed { i, b ->
                val on = b.onParam?.let { (params[it] ?: 0f) > 0.5f } ?: true
                val p = bandPos(b, w, h)
                val r = if (b.key == selected) 11f * density else 8f * density
                drawCircle(if (on) b.color else b.color.copy(alpha = 0.3f), r, p)
                drawCircle(Color.White, r, p, style = Stroke(if (b.key == selected) 2.5f else 1f))
                drawText(measurer, if (i == 0) "L" else if (i == EQ_BANDS.size - 1) "H" else "$i", Offset(p.x - 4f, p.y - 7f), TextStyle(color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold))
            }
        }
        // band strip
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EQ_BANDS.forEach { b ->
                val on = b.onParam?.let { (params[it] ?: 0f) > 0.5f } ?: true
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (b.key == selected) b.color else DawColors.surfaceHigh)
                        .border(1.dp, if (on) b.color else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { selected = b.key }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(b.label, fontSize = 10.sp, color = if (b.key == selected) Color.Black else DawColors.text)
                }
            }
        }
        val band = EQ_BANDS.first { it.key == selected }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (band.onParam != null) {
                val on = (params[band.onParam] ?: 0f) > 0.5f
                PowerButton(on) { set(band.onParam, if (on) 0f else 1f) }
            }
            val specs = listOfNotNull(band.freqParam, band.gainParam, band.qParam).map { specOf(it) } + specOf("output")
            specs.forEach { spec ->
                val v = params[spec.id] ?: spec.default
                RotaryKnob(
                    value = spec.toNormalized(v),
                    onChange = { set(spec.id, spec.fromNormalized(it)) },
                    label = if (spec.id == "output") "Gain" else spec.name.substringAfter(" ").replace("Cut ", "").ifBlank { spec.name },
                    valueText = spec.format(v),
                    default = spec.toNormalized(spec.default),
                    bipolar = spec.min < 0f && spec.max > 0f,
                    color = if (spec.id == "output") DawColors.accent else band.color,
                    diameter = 50.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Dynamics

/** Compressor-family window: transfer curve, gain-reduction meter and knobs. */
@Composable
private fun DynamicsEditor(vm: DawViewModel, ownerId: String, slot: PluginSlot, d: PluginDescriptor) {
    val params = d.defaults() + slot.params
    var gr by remember { mutableFloatStateOf(0f) }
    var grPeak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(slot.id) {
        while (true) {
            val g = vm.pluginInstance(slot.id)?.gainReductionDb ?: 0f
            gr = g
            grPeak = if (g < grPeak) g else grPeak * 0.97f
            delay(40)
        }
    }
    val measurer = rememberTextMeasurer()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d.id == "compressor" || d.id == "multipressor") {
                val thr = params["threshold"] ?: params["midThr"] ?: -20f
                val ratio = params["ratio"] ?: 4f
                val knee = params["knee"] ?: 6f
                Canvas(Modifier.size(150.dp).clip(RoundedCornerShape(6.dp)).background(DawColors.lcdBackground)) {
                    val w = size.width
                    val h = size.height
                    fun xOf(db: Float) = (db + 60f) / 60f * w
                    fun yOf(db: Float) = h - (db + 60f) / 60f * h
                    for (db in listOf(-48f, -36f, -24f, -12f)) {
                        drawLine(DawColors.gridBeat, Offset(xOf(db), 0f), Offset(xOf(db), h))
                        drawLine(DawColors.gridBeat, Offset(0f, yOf(db)), Offset(w, yOf(db)))
                    }
                    drawLine(DawColors.gridBar, Offset(0f, h), Offset(w, 0f))
                    val path = Path()
                    var i = 0
                    var inDb = -60f
                    while (inDb <= 0f) {
                        val out = inDb + curveGain(inDb, thr, ratio, knee)
                        if (i++ == 0) path.moveTo(xOf(inDb), yOf(out)) else path.lineTo(xOf(inDb), yOf(out))
                        inDb += 0.5f
                    }
                    drawPath(path, DawColors.meterYellow, style = Stroke(2.5f))
                    drawLine(DawColors.accent.copy(alpha = 0.6f), Offset(xOf(thr), 0f), Offset(xOf(thr), h), strokeWidth = 1f)
                    drawText(measurer, "Threshold", Offset(xOf(thr) + 3f, 4f), TextStyle(color = DawColors.accent, fontSize = 8.sp))
                }
            }
            // gain reduction meter
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Gain Reduction", fontSize = 10.sp, color = DawColors.textDim)
                Canvas(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(4.dp)).background(DawColors.lcdBackground)) {
                    val w = size.width
                    fun xOf(db: Float) = w - (-db / 30f).coerceIn(0f, 1f) * w
                    for (db in listOf(-3f, -6f, -10f, -15f, -20f, -30f)) {
                        drawLine(DawColors.gridBar, Offset(xOf(db), size.height * 0.6f), Offset(xOf(db), size.height))
                        drawText(measurer, "${db.toInt()}", Offset(xOf(db) + 2f, 1f), TextStyle(color = DawColors.lcdLabel, fontSize = 7.sp))
                    }
                    drawRect(DawColors.meterYellow, Offset(xOf(gr), size.height * 0.55f), Size(w - xOf(gr), size.height * 0.4f))
                    drawLine(DawColors.meterRed, Offset(xOf(grPeak), size.height * 0.5f), Offset(xOf(grPeak), size.height), strokeWidth = 2f)
                }
                Text(
                    "%.1f dB".format(gr),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (gr < -0.1f) DawColors.meterYellow else DawColors.lcdDim,
                )
            }
        }
        KnobPanel(d.params, slot.params) { id, v -> vm.setPluginParam(ownerId, slot.id, id, v) }
    }
}

/** Static soft-knee compression curve for display (mirrors the engine's gain computer). */
private fun curveGain(levelDb: Float, thresholdDb: Float, ratio: Float, kneeDb: Float): Float {
    val over = levelDb - thresholdDb
    val slope = 1f / ratio - 1f
    return when {
        kneeDb > 0f && abs(over) <= kneeDb / 2f -> slope * (over + kneeDb / 2f) * (over + kneeDb / 2f) / (2f * kneeDb)
        over > 0f -> slope * over
        else -> 0f
    }
}


private val METER_IDS = setOf("loudness_meter", "multimeter", "tuner", "pitch_correction")

/** Logic's side-chain menu in the plugin header: key the detector from another track. */
@Composable
private fun SidechainSelector(vm: DawViewModel, ownerId: String, slot: PluginSlot) {
    val tracks = vm.project.tracks.filter { it.id != ownerId }
    val current = slot.sidechainTrackId?.let { id -> vm.project.track(id)?.name } ?: "없음 (자기 신호)"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text("Side Chain", fontSize = 11.sp, color = DawColors.textDim, modifier = Modifier.width(80.dp))
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(DawColors.lcdBackground)) {
            MenuButton(current, color = DawColors.lcdText) { close ->
                DropdownMenuItem(text = { Text("없음 (자기 신호)") }, onClick = { vm.setSidechain(ownerId, slot.id, null); close() })
                tracks.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { vm.setSidechain(ownerId, slot.id, t.id); close() }) }
            }
        }
    }
}

/** Big numeric readouts for metering plugins (Loudness Meter, MultiMeter, Tuner, Pitch Correction). */
@Composable
private fun MeterReadouts(vm: DawViewModel, slot: PluginSlot, id: String) {
    var values by remember { mutableStateOf(emptyMap<String, Float>()) }
    var spectrum by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(slot.id) {
        val buf = FloatArray(4096)
        while (true) {
            val inst = vm.pluginInstance(slot.id)
            values = inst?.readouts ?: emptyMap()
            if (id == "multimeter") inst?.analyzer?.let { tap ->
                tap.snapshot(buf)
                spectrum = withContext(Dispatchers.Default) { PluginAnalysis.spectrum(buf, vm.sampleRate, 120) }
            }
            delay(80)
        }
    }
    fun big(label: String, value: String, color: Color = DawColors.lcdText) = @Composable {
        Column(Modifier.clip(RoundedCornerShape(6.dp)).background(DawColors.lcdBackground).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 9.sp, color = DawColors.lcdLabel)
        }
    }
    fun lufs(v: Float?) = if (v == null || v <= -69.9f) "-∞" else "%.1f".format(v)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (id) {
            "loudness_meter" -> {
                big("MOMENTARY LUFS", lufs(values["momentary"]))()
                big("SHORT-TERM LUFS", lufs(values["shortTerm"]))()
                val integ = values["integrated"]
                val target = slot.params["target"] ?: -14f
                big("INTEGRATED LUFS", lufs(integ), if (integ != null && integ > target + 1) DawColors.meterYellow else DawColors.meterGreen)()
                big("TRUE PEAK dBTP", lufs(values["truePeak"]), if ((values["truePeak"] ?: -70f) > -1f) DawColors.meterRed else DawColors.lcdText)()
            }
            "tuner", "pitch_correction" -> {
                val midi = values["midi"] ?: 0f
                if (midi > 0f) {
                    val cents = ((midi - kotlin.math.round(midi)) * 100).toInt()
                    big("NOTE", com.musiccut.core.dsp.PitchDetector.noteName(midi))()
                    big("CENTS", "%+d".format(cents), if (kotlin.math.abs(cents) < 5) DawColors.meterGreen else DawColors.meterYellow)()
                } else big("NOTE", "—")()
                values["cents"]?.let { big("CORRECTION", "%+.0f ct".format(it))() }
            }
            "multimeter" -> {
                big("CORRELATION", "%+.2f".format(values["correlation"] ?: 1f), if ((values["correlation"] ?: 1f) < 0f) DawColors.meterRed else DawColors.meterGreen)()
                big("PEAK L", lufs(values["peakL"]?.let { 20f * kotlin.math.log10(it.coerceAtLeast(1e-6f)) }))()
                big("PEAK R", lufs(values["peakR"]?.let { 20f * kotlin.math.log10(it.coerceAtLeast(1e-6f)) }))()
            }
        }
    }
    if (id == "multimeter") {
        val sp = spectrum
        Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(6.dp)).background(DawColors.lcdBackground)) {
            if (sp != null) {
                val bw = size.width / sp.size
                sp.forEachIndexed { i, db ->
                    val h = ((db + 96f) / 96f).coerceIn(0f, 1f) * size.height
                    drawRect(DawColors.meterGreen, Offset(i * bw, size.height - h), Size(bw - 1f, h))
                }
            }
        }
    }
}

/** 31-band Graphic / Match EQ: drag the bars; Match EQ adds the learn buttons. */
@Composable
private fun GraphicEqEditor(vm: DawViewModel, ownerId: String, slot: PluginSlot, d: PluginDescriptor) {
    val params = d.defaults() + slot.params
    val paramsRef by rememberUpdatedState(params)
    val bands = com.musiccut.core.plugin.effects.THIRD_OCTAVE.size
    val measurer = rememberTextMeasurer()
    if (d.id == "match_eq") {
        Text(
            "1) 레퍼런스 곡(원하는 음색)의 리전을 선택 → 템플릿 학습  2) '현재 학습 + 매치'로 이 트랙을 레퍼런스처럼 맞춥니다.",
            fontSize = 11.sp, color = DawColors.textDim, modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            LogicButton(text = "템플릿 학습", width = 100.dp, active = slot.id in vm.matchTemplateReady) { vm.learnMatchTemplate(slot.id) }
            LogicButton(text = "현재 학습 + 매치", width = 130.dp, enabled = slot.id in vm.matchTemplateReady) { vm.learnMatchCurrent(ownerId, slot.id) }
            LogicButton(text = "평탄화", width = 70.dp) {
                (0 until bands).forEach { vm.setPluginParam(ownerId, slot.id, "g$it", 0f) }
            }
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DawColors.lcdBackground)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val bw = size.width.toFloat() / bands
                    val b = (change.position.x / bw).toInt().coerceIn(0, bands - 1)
                    val g = ((size.height / 2f - change.position.y) / (size.height / 2f) * 12f).coerceIn(-12f, 12f)
                    vm.setPluginParam(ownerId, slot.id, "g$b", (g * 2).roundToIntSafe() / 2f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { o ->
                    val b = (o.x / (size.width.toFloat() / bands)).toInt().coerceIn(0, bands - 1)
                    vm.setPluginParam(ownerId, slot.id, "g$b", 0f)
                })
            }
    ) {
        val bw = size.width / bands
        val mid = size.height / 2
        drawLine(DawColors.gridBar, Offset(0f, mid), Offset(size.width, mid))
        for (db in listOf(-6f, 6f)) drawLine(DawColors.gridBeat, Offset(0f, mid - db / 12f * mid), Offset(size.width, mid - db / 12f * mid))
        for (b in 0 until bands) {
            val g = paramsRef["g$b"] ?: 0f
            val y = mid - g / 12f * mid
            drawRect(if (g >= 0) DawColors.accent else DawColors.meterYellow, Offset(b * bw + 1f, minOf(y, mid)), Size(bw - 2f, kotlin.math.abs(y - mid).coerceAtLeast(1.5f)))
            if (b % 3 == 0) {
                val f = com.musiccut.core.plugin.effects.THIRD_OCTAVE[b]
                drawText(measurer, if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", Offset(b * bw, size.height - 12f), TextStyle(color = DawColors.lcdLabel, fontSize = 7.sp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val extra = d.params.filter { !it.id.startsWith("g") }
    KnobPanel(extra, slot.params) { id, v -> vm.setPluginParam(ownerId, slot.id, id, v) }
}

private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
