package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.MASTER_ID
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.plugin.PluginAnalysis
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.PluginSlot
import com.musiccut.core.project.Send
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.pow

private enum class StripKind(val label: String) { AUDIO("Audio"), AUX("Aux"), OUTPUT("Output") }

private data class StripModel(
    val ownerId: String,
    val kind: StripKind,
    val name: String,
    val color: Color,
    val plugins: List<PluginSlot>,
    val volumeDb: Float,
    val pan: Float?,
    val muted: Boolean?,
    val solo: Boolean?,
    val sends: List<Send>?,
    val selected: Boolean,
    val outputBusId: String? = null,
    val groupId: Int? = null,
)

/** Logic-style mixer: audio channel strips, aux strips and the Stereo Out. */
@Composable
fun MixerView(
    vm: DawViewModel,
    onEditPlugin: (ownerId: String, slotId: String) -> Unit,
    onAddPlugin: (ownerId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = vm.project
    Row(
        modifier.fillMaxSize().background(DawColors.background).horizontalScroll(rememberScrollState()).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        project.tracks.forEach { t ->
            ChannelStrip(
                vm,
                StripModel(t.id, StripKind.AUDIO, t.name, DawColors.track(t.color), t.plugins, t.volumeDb, t.pan, t.muted, t.solo, t.sends, t.id == vm.selectedTrackId, t.outputBusId, t.groupId),
                onEditPlugin, onAddPlugin,
            )
        }
        project.buses.forEach { b ->
            ChannelStrip(
                vm,
                StripModel(b.id, StripKind.AUX, b.name, DawColors.track(b.color), b.plugins, b.volumeDb, b.pan, b.muted, null, null, false),
                onEditPlugin, onAddPlugin,
            )
        }
        ChannelStrip(
            vm,
            StripModel(MASTER_ID, StripKind.OUTPUT, "Stereo Out", DawColors.textDim, project.masterPlugins, project.masterVolumeDb, null, null, null, null, false),
            onEditPlugin, onAddPlugin,
        )
    }
}

/** Inspector variant: the selected track's strip next to the Stereo Out, like Logic's inspector. */
@Composable
fun InspectorStrips(vm: DawViewModel, onEditPlugin: (String, String) -> Unit, onAddPlugin: (String) -> Unit) {
    val project = vm.project
    val t = project.track(vm.selectedTrackId ?: "") ?: project.tracks.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(470.dp)) {
        if (t != null) {
            ChannelStrip(
                vm,
                StripModel(t.id, StripKind.AUDIO, t.name, DawColors.track(t.color), t.plugins, t.volumeDb, t.pan, t.muted, t.solo, t.sends, false),
                onEditPlugin, onAddPlugin, width = 96.dp,
            )
        }
        ChannelStrip(
            vm,
            StripModel(MASTER_ID, StripKind.OUTPUT, "Stereo Out", DawColors.textDim, project.masterPlugins, project.masterVolumeDb, null, null, null, null, false),
            onEditPlugin, onAddPlugin, width = 96.dp,
        )
    }
}

@Composable
private fun ChannelStrip(
    vm: DawViewModel,
    m: StripModel,
    onEditPlugin: (String, String) -> Unit,
    onAddPlugin: (String) -> Unit,
    width: Dp = 100.dp,
) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(if (m.selected) DawColors.laneSelected else DawColors.panel)
            .border(0.5.dp, DawColors.panelBorder, RoundedCornerShape(4.dp))
            .clickable { if (m.kind == StripKind.AUDIO) vm.selectTrack(m.ownerId) }
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SlotLabel(m.kind.label, dim = true)
        GainReductionMeter(vm, m.plugins)
        EqThumbnail(m.plugins) { vm.ensureChannelEq(m.ownerId)?.let { onEditPlugin(m.ownerId, it) } }
        SlotLabel(
            when (m.kind) {
                StripKind.AUDIO -> "Input 1"
                StripKind.AUX -> "Bus"
                StripKind.OUTPUT -> "Output 1-2"
            },
            dim = true,
        )
        // Audio FX
        Column(
            Modifier.fillMaxWidth().heightIn(max = 110.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            m.plugins.forEach { slot ->
                val d = PluginRegistry.find(slot.pluginId)
                PluginSlotButton(d?.name ?: slot.pluginId, bypassed = slot.bypassed) { onEditPlugin(m.ownerId, slot.id) }
            }
            EmptySlot("Audio FX") { onAddPlugin(m.ownerId) }
        }
        // Sends
        if (m.sends != null) SendsSection(vm, m.ownerId, m.sends)
        if (m.kind == StripKind.AUDIO) {
            // Output (Logic: Stereo Out or a bus / summing stack) and Group slots
            Box {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.clickable { open = true }) { SlotLabel(m.outputBusId?.let { vm.project.bus(it)?.name } ?: "Stereo Out", dim = false) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Stereo Out") }, onClick = { vm.setTrackOutput(m.ownerId, null); open = false })
                    vm.project.buses.forEach { b -> DropdownMenuItem(text = { Text(b.name) }, onClick = { vm.setTrackOutput(m.ownerId, b.id); open = false }) }
                }
            }
            Box {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.clickable { open = true }) { SlotLabel(m.groupId?.let { "Group $it" } ?: "Group", dim = m.groupId == null) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("없음") }, onClick = { vm.setGroup(m.ownerId, null); open = false })
                    (1..8).forEach { g -> DropdownMenuItem(text = { Text("Group $g") }, onClick = { vm.setGroup(m.ownerId, g); open = false }) }
                }
            }
        } else SlotLabel(if (m.kind == StripKind.OUTPUT) "Stereo" else "Stereo Out", dim = false)
        // pan
        if (m.pan != null) {
            val pan = m.pan
            RotaryKnob(
                value = (pan + 1f) / 2f,
                onChange = { v ->
                    val p = v * 2f - 1f
                    when (m.kind) {
                        StripKind.AUX -> vm.setBusPan(m.ownerId, p)
                        else -> vm.setPan(m.ownerId, p)
                    }
                },
                default = 0.5f,
                bipolar = true,
                diameter = 30.dp,
            )
        } else Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ValueBox(if (m.volumeDb <= -59.9f) "-∞" else "%.1f".format(m.volumeDb))
            PeakBox(vm, m.ownerId)
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
            Fader(
                m.volumeDb,
                { db ->
                    when (m.kind) {
                        StripKind.AUDIO -> vm.setVolume(m.ownerId, db)
                        StripKind.AUX -> vm.setBusVolume(m.ownerId, db)
                        StripKind.OUTPUT -> vm.setMasterVolume(db)
                    }
                },
                Modifier.width(34.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(3.dp))
            StripMeter(vm, m.ownerId, Modifier.width(12.dp).fillMaxHeight())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (m.muted != null) {
                ToggleChip("M", m.muted, DawColors.mute) {
                    if (m.kind == StripKind.AUX) vm.toggleBusMute(m.ownerId) else vm.toggleMute(m.ownerId)
                }
            }
            if (m.solo != null) ToggleChip("S", m.solo, DawColors.solo) { vm.toggleSolo(m.ownerId) }
        }
        Box(
            Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(3.dp)).background(m.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(m.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SlotLabel(text: String, dim: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(17.dp).clip(RoundedCornerShape(3.dp)).background(DawColors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 9.sp, color = if (dim) DawColors.textDim else DawColors.text, maxLines = 1)
    }
}

@Composable
private fun PluginSlotButton(name: String, bypassed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(19.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (bypassed) Brush.verticalGradient(listOf(DawColors.surfaceHigh, DawColors.surface))
                else Brush.verticalGradient(listOf(Color(0xFF5B8FE0), Color(0xFF3569BC)))
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (bypassed) DawColors.textDim else Color.White)
    }
}

@Composable
private fun EmptySlot(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(17.dp).clip(RoundedCornerShape(3.dp)).background(DawColors.lcdBackground).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 8.sp, color = DawColors.lcdLabel)
    }
}

@Composable
private fun SendsSection(vm: DawViewModel, trackId: String, sends: List<Send>) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        sends.forEach { send -> key(send.busId) {
            val bus = vm.project.bus(send.busId)
            var sendMenu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(DawColors.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    Text(
                        (if (send.preFader) "▸" else "") + (bus?.name ?: "?"),
                        fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = DawColors.text,
                        modifier = Modifier.clickable { sendMenu = true }.padding(start = 3.dp),
                    )
                    DropdownMenu(expanded = sendMenu, onDismissRequest = { sendMenu = false }) {
                        DropdownMenuItem(text = { Text(if (send.preFader) "Post Fader로" else "Pre Fader로") }, onClick = { vm.toggleSendPre(trackId, send.busId); sendMenu = false })
                        DropdownMenuItem(text = { Text("센드 삭제", color = DawColors.record) }, onClick = { vm.removeSend(trackId, send.busId); sendMenu = false })
                    }
                }
                RotaryKnob(
                    value = ((send.levelDb + 60f) / 66f).coerceIn(0f, 1f),
                    onChange = { vm.setSendLevel(trackId, send.busId, it * 66f - 60f) },
                    default = 50f / 66f,
                    diameter = 18.dp,
                    color = DawColors.meterGreen,
                    modifier = Modifier.width(26.dp),
                )
            }
        } }
        Box {
            EmptySlot("Send") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                vm.project.buses.forEach { b ->
                    DropdownMenuItem(text = { Text(b.name) }, onClick = { vm.addSend(trackId, b.id); menu = false })
                }
                if (vm.project.buses.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(text = { Text("새 Aux + ChromaVerb") }, onClick = { vm.addSend(trackId, vm.addBus("chromaverb")); menu = false })
                DropdownMenuItem(text = { Text("새 Aux + Tape Delay") }, onClick = { vm.addSend(trackId, vm.addBus("tape_delay")); menu = false })
                DropdownMenuItem(text = { Text("새 Aux (빈 채널)") }, onClick = { vm.addSend(trackId, vm.addBus()); menu = false })
            }
        }
    }
}

@Composable
private fun ValueBox(text: String) {
    Box(Modifier.clip(RoundedCornerShape(2.dp)).background(DawColors.lcdBackground).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(text, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DawColors.lcdText)
    }
}

/** Peak-hold readout (tap to reset), red when clipping. */
@Composable
private fun PeakBox(vm: DawViewModel, id: String) {
    val meters by vm.meters.collectAsState()
    var peak by remember { mutableFloatStateOf(0f) }
    val level = meters[id] ?: 0f
    LaunchedEffect(level) { if (level > peak) peak = level }
    val db = if (peak <= 0f) null else 20f * log10(peak)
    Box(
        Modifier.clip(RoundedCornerShape(2.dp)).background(if (db != null && db > 0f) DawColors.meterRed else DawColors.lcdBackground)
            .clickable { peak = 0f }.padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(db?.let { "%.1f".format(it) } ?: "-∞", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DawColors.lcdText)
    }
}

@Composable
private fun StripMeter(vm: DawViewModel, id: String, modifier: Modifier) {
    val meters by vm.meters.collectAsState()
    VerticalMeter(meters[id] ?: 0f, modifier)
}

@Composable
fun VerticalMeter(level: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(2.dp)).background(DawColors.lcdBackground)) {
        val db = if (level <= 0f) -60f else (20f * log10(level)).coerceIn(-60f, 6f)
        val f = dbToPos(db)
        val h = size.height * f
        drawRect(
            Brush.verticalGradient(0f to DawColors.meterRed, 0.1f to DawColors.meterYellow, 0.28f to DawColors.meterGreen, 1f to DawColors.meterGreen, startY = 0f, endY = size.height),
            Offset(0f, size.height - h), Size(size.width, h),
        )
        // segment lines
        var y = 0f
        while (y < size.height) {
            drawLine(DawColors.lcdBackground, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += 4f
        }
    }
}

/** Gain reduction of the first dynamics plugin on the strip (Logic shows this above the EQ). */
@Composable
private fun GainReductionMeter(vm: DawViewModel, plugins: List<PluginSlot>) {
    val dyn = plugins.firstOrNull { !it.bypassed && it.pluginId in DYNAMICS_IDS }
    var gr by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(dyn?.id) {
        while (dyn != null) {
            gr = vm.pluginInstance(dyn.id)?.gainReductionDb ?: 0f
            delay(50)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(2.dp)).background(DawColors.lcdBackground)) {
        if (dyn != null) {
            val f = (-gr / 20f).coerceIn(0f, 1f)
            drawRect(DawColors.meterYellow, Offset(size.width * (1 - f), 0f), Size(size.width * f, size.height))
        }
    }
}

val DYNAMICS_IDS = setOf("compressor", "limiter", "noise_gate", "deesser", "multipressor")

/** EQ thumbnail showing the Channel EQ curve; tap to open (or insert) the Channel EQ. */
@Composable
private fun EqThumbnail(plugins: List<PluginSlot>, onClick: () -> Unit) {
    val eq = plugins.firstOrNull { it.pluginId == "channel_eq" }
    val curve = remember(eq?.params, eq?.bypassed) {
        if (eq == null || eq.bypassed) null
        else PluginAnalysis.frequencyResponse(PluginRegistry.find("channel_eq")!!, eq.params, points = 48)
    }
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(3.dp)).background(DawColors.lcdBackground)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
    ) {
        val mid = size.height / 2
        drawLine(DawColors.gridBar, Offset(0f, mid), Offset(size.width, mid))
        if (curve == null) {
            drawText(measurer, "EQ", Offset(size.width / 2 - 8f, mid - 7f), TextStyle(color = DawColors.lcdLabel, fontSize = 9.sp))
        } else {
            val path = Path()
            curve.forEachIndexed { i, db ->
                val x = i.toFloat() / (curve.size - 1) * size.width
                val y = mid - (db / 24f).coerceIn(-1f, 1f) * (mid - 2f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, DawColors.meterGreen, style = Stroke(1.5f))
        }
    }
}

private const val FADER_MIN_DB = -60f
private const val FADER_MAX_DB = 6f

private fun dbToPos(db: Float): Float {
    if (db <= FADER_MIN_DB) return 0f
    val lin = ((db - FADER_MIN_DB) / (FADER_MAX_DB - FADER_MIN_DB)).coerceIn(0f, 1f)
    return lin.toDouble().pow(1.0 / 1.6).toFloat()
}

private fun posToDb(pos: Float): Float {
    val lin = pos.coerceIn(0f, 1f).toDouble().pow(1.6).toFloat()
    return FADER_MIN_DB + lin * (FADER_MAX_DB - FADER_MIN_DB)
}

/** Vertical fader with dB scale; double-tap resets to 0 dB. */
@Composable
fun Fader(valueDb: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(valueDb)
    val change by rememberUpdatedState(onChange)
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) }
            .pointerInput(Unit) {
                detectVerticalDragGestures { c, dy ->
                    c.consume()
                    change(posToDb(dbToPos(current) - dy / size.height))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val capH = 30f
        val usable = h - capH
        fun yOf(db: Float) = capH / 2 + usable * (1 - dbToPos(db))
        // scale
        for (db in listOf(6f, 0f, -6f, -12f, -24f, -40f)) {
            val y = yOf(db)
            drawLine(DawColors.textDim.copy(alpha = 0.5f), Offset(0f, y), Offset(5f, y), strokeWidth = 1f)
            drawText(measurer, "${db.toInt()}", Offset(w * 0.62f, y - 6f), TextStyle(color = DawColors.textDim, fontSize = 7.sp))
        }
        drawRoundRect(DawColors.knobTrack, Offset(w * 0.38f - 2f, capH / 2), Size(4f, usable), CornerRadius(2f, 2f))
        val y = yOf(valueDb)
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFE4E4E8), DawColors.faderCap, Color(0xFF8E8E93)), startY = y - capH / 2, endY = y + capH / 2),
            Offset(w * 0.38f - 12f, y - capH / 2), Size(24f, capH), CornerRadius(3f, 3f),
        )
        drawLine(Color.Black, Offset(w * 0.38f - 10f, y), Offset(w * 0.38f + 10f, y), strokeWidth = 2f)
    }
}
