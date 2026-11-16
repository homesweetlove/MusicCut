package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.BottomPane
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.engine.Mixer
import kotlin.math.log10

/**
 * Logic's control bar: view toggles on the left, transport + LCD in the middle, metronome /
 * count-in, master level meter and undo / redo / menu on the right. With [twoRows] (portrait
 * phones) the transport + LCD move to a second row so nothing has to be scrolled.
 */
@Composable
fun ControlBar(
    vm: DawViewModel,
    onMenu: () -> Unit,
    onRecord: () -> Unit,
    onProjectSettings: () -> Unit,
    twoRows: Boolean = false,
    compactLcd: Boolean = false,
) {
    if (twoRows) {
        Column(Modifier.fillMaxWidth().background(DawColors.controlBarBrush).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ViewToggles(vm, onMenu)
                ProjectTitle(vm)
                RightTools(vm)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Transport(vm, onRecord)
                Lcd(vm, onProjectSettings, compact = true)
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .background(DawColors.controlBarBrush)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ViewToggles(vm, onMenu)
            ProjectTitle(vm)
            Spacer(Modifier.width(4.dp))
            Transport(vm, onRecord)
            Spacer(Modifier.width(4.dp))
            Lcd(vm, onProjectSettings, compact = compactLcd)
            Spacer(Modifier.width(4.dp))
            RightTools(vm)
        }
    }
}

/** Single-line transport shown when the header is collapsed all the way up. */
@Composable
fun MiniTransportBar(vm: DawViewModel, onMenu: () -> Unit, onRecord: () -> Unit) {
    val position by vm.position.collectAsState()
    val (bars, clock) = lcdText(vm.project, position)
    Row(
        Modifier.fillMaxWidth().background(DawColors.controlBarBrush).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LogicButton(icon = Icons.Filled.Menu, contentDescription = "메뉴", height = 24.dp, onClick = onMenu)
        Transport(vm, onRecord, height = 24.dp)
        Text(bars, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DawColors.lcdText, modifier = Modifier.padding(horizontal = 6.dp))
        Text(clock, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = DawColors.lcdDim)
        Spacer(Modifier.width(6.dp))
        LogicButton(icon = Icons.AutoMirrored.Filled.Undo, contentDescription = "실행 취소", enabled = vm.canUndo, height = 24.dp, onClick = vm::undo)
        LogicButton(icon = Icons.AutoMirrored.Filled.Redo, contentDescription = "다시 실행", enabled = vm.canRedo, height = 24.dp, onClick = vm::redo)
    }
}

@Composable
private fun ViewToggles(vm: DawViewModel, onMenu: () -> Unit) {
    LogicButton(icon = Icons.Filled.Menu, contentDescription = "메뉴", onClick = onMenu)
    LogicButton(icon = Icons.Filled.Info, contentDescription = "인스펙터", active = vm.showInspector) { vm.showInspector = !vm.showInspector }
    LogicButton(icon = Icons.Filled.Tune, contentDescription = "믹서", active = vm.bottomPane == BottomPane.MIXER) {
        vm.bottomPane = if (vm.bottomPane == BottomPane.MIXER) BottomPane.NONE else BottomPane.MIXER
    }
    LogicButton(icon = Icons.Filled.ContentCut, contentDescription = "에디터", active = vm.bottomPane == BottomPane.EDITOR) {
        vm.bottomPane = if (vm.bottomPane == BottomPane.EDITOR) BottomPane.NONE else BottomPane.EDITOR
    }
}

@Composable
private fun ProjectTitle(vm: DawViewModel) {
    Column(Modifier.width(92.dp).padding(horizontal = 4.dp)) {
        Text(
            (if (vm.dirty) "• " else "") + vm.project.name,
            fontSize = 11.sp, color = DawColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("${vm.project.tracks.size} tracks", fontSize = 9.sp, color = DawColors.textDim)
    }
}

@Composable
private fun Transport(vm: DawViewModel, onRecord: () -> Unit, height: androidx.compose.ui.unit.Dp = 28.dp) {
    val playing by vm.playing.collectAsState()
    val project = vm.project
    LogicButton(icon = Icons.Filled.SkipPrevious, contentDescription = "처음으로", height = height, onClick = vm::rewind)
    LogicButton(icon = Icons.Filled.FastRewind, contentDescription = "한 마디 뒤로", height = height) { vm.stepBar(-1) }
    LogicButton(icon = Icons.Filled.FastForward, contentDescription = "한 마디 앞으로", height = height) { vm.stepBar(1) }
    LogicButton(icon = Icons.Filled.Stop, contentDescription = "정지", height = height, onClick = vm::stop)
    LogicButton(
        icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        contentDescription = "재생",
        active = playing,
        activeColor = DawColors.meterGreen,
        width = 42.dp,
        height = height,
        onClick = vm::togglePlay,
    )
    LogicButton(icon = Icons.Filled.FiberManualRecord, contentDescription = "녹음", active = vm.isRecording, activeColor = DawColors.record, height = height, onClick = onRecord)
    LogicButton(icon = Icons.Filled.Repeat, contentDescription = "사이클", active = project.cycleEnabled, activeColor = DawColors.cycle, height = height, onClick = vm::toggleCycle)
}

@Composable
private fun RightTools(vm: DawViewModel) {
    LogicButton(text = "1234", active = vm.countIn, width = 42.dp, contentDescription = "카운트인") { vm.countIn = !vm.countIn }
    LogicButton(icon = Icons.Filled.Timer, contentDescription = "메트로놈", active = vm.metronomeOn, onClick = vm::toggleMetronome)
    MasterMeter(vm)
    Spacer(Modifier.width(4.dp))
    LogicButton(icon = Icons.AutoMirrored.Filled.Undo, contentDescription = "실행 취소", enabled = vm.canUndo, onClick = vm::undo)
    LogicButton(icon = Icons.AutoMirrored.Filled.Redo, contentDescription = "다시 실행", enabled = vm.canRedo, onClick = vm::redo)
    LogicButton(icon = Icons.Filled.ViewDay, contentDescription = "트랙 높이") {
        vm.trackHeight = if (vm.trackHeight >= 120f) 56f else vm.trackHeight + 32f
    }
}

/** The LCD: bar/beat position, clock time, tempo, time signature and key. */
@Composable
private fun Lcd(vm: DawViewModel, onProjectSettings: () -> Unit, compact: Boolean = false) {
    val position by vm.position.collectAsState()
    val project = vm.project
    val (bars, clock) = lcdText(project, position)
    Row(
        Modifier
            .height(if (compact) 32.dp else 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DawColors.lcdBackground)
            .border(1.dp, DawColors.panelBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LcdField(bars, "BAR  BEAT", width = if (compact) 74 else 86, small = compact)
        LcdField(clock, "TIME", width = 78, small = true)
        Row(
            Modifier.clickable(onClick = onProjectSettings),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LcdField("%.0f".format(project.tempo), "TEMPO", width = 40, small = compact)
            LcdField("${project.beatsPerBar}/4", "SIG", width = 36, small = compact)
            LcdField(project.keySignature.replace(" major", " maj").replace(" minor", " min"), "KEY", width = 52, small = true)
        }
    }
}

@Composable
private fun LcdField(value: String, label: String, width: Int, small: Boolean = false) {
    Column(Modifier.width(width.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = if (small) 12.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = DawColors.lcdText,
            maxLines = 1,
        )
        Text(label, fontSize = 7.sp, color = DawColors.lcdLabel, maxLines = 1)
    }
}

/** Stereo output meter (like the CPU / level area of Logic's LCD). */
@Composable
private fun MasterMeter(vm: DawViewModel) {
    val meters by vm.meters.collectAsState()
    val l = meters["${Mixer.MASTER}_L"] ?: 0f
    val r = meters["${Mixer.MASTER}_R"] ?: 0f
    Canvas(Modifier.width(64.dp).height(20.dp).clip(RoundedCornerShape(3.dp)).background(DawColors.lcdBackground)) {
        val barH = size.height / 2 - 2f
        fun bar(level: Float, y: Float) {
            val db = if (level <= 0f) -60f else (20f * log10(level)).coerceIn(-60f, 6f)
            val f = ((db + 60f) / 66f).coerceIn(0f, 1f)
            drawRect(
                Brush.horizontalGradient(0f to DawColors.meterGreen, 0.72f to DawColors.meterYellow, 0.9f to DawColors.meterRed, endX = size.width),
                Offset(2f, y), Size((size.width - 4f) * f, barH),
            )
        }
        bar(l, 1.5f)
        bar(r, size.height / 2 + 0.5f)
    }
}

