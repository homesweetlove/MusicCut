package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.project.Track

/** Logic-style track header: number, icon, name, M / S / R, pan knob, volume slider and meter. */
@Composable
fun TrackHeader(
    vm: DawViewModel,
    track: Track,
    index: Int,
    height: Dp,
    selected: Boolean,
    onOpenPlugins: () -> Unit,
    onOpenTakes: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }
    val color = DawColors.track(track.color)
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(if (selected) DawColors.laneSelected else DawColors.surface)
            .clickable { vm.selectTrack(track.id) }
            .drawBehind { drawLine(DawColors.laneSeparator, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f)) },
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(
            Modifier.weight(1f).padding(start = 5.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", fontSize = 10.sp, color = DawColors.textDim, modifier = Modifier.width(16.dp))
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(DawColors.darker(color, 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (track.armed) Icons.Filled.Mic else Icons.Filled.GraphicEq, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
                Text(
                    track.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else DawColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 5.dp),
                )
                Box {
                    Box(Modifier.size(22.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MoreVert, "트랙 메뉴", tint = DawColors.textDim, modifier = Modifier.size(16.dp))
                    }
                    TrackMenu(vm, track, menu, onDismiss = { menu = false }, onRename = { renaming = true }, onColor = { colorPicker = true }, onOpenPlugins = onOpenPlugins, onOpenTakes = onOpenTakes)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ToggleChip("M", track.muted, DawColors.mute) { vm.toggleMute(track.id) }
                ToggleChip("S", track.solo, DawColors.solo) { vm.toggleSolo(track.id) }
                ToggleChip("R", track.armed, DawColors.record) { vm.toggleArm(track.id) }
                Spacer(Modifier.weight(1f))
                if (track.takeLanes.isNotEmpty()) {
                    Text(
                        "T${track.takeLanes.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DawColors.cycle,
                        modifier = Modifier.clickable(onClick = onOpenTakes).padding(horizontal = 3.dp),
                    )
                }
                track.groupId?.let { Text("G$it", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DawColors.meterGreen, modifier = Modifier.padding(horizontal = 2.dp)) }
                if (track.outputBusId != null) Text("→", fontSize = 11.sp, color = DawColors.accent, modifier = Modifier.padding(horizontal = 2.dp))
                Text(
                    if (track.plugins.isEmpty()) "FX" else "FX${track.plugins.size}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (track.plugins.isEmpty()) DawColors.textDim else DawColors.accent,
                    modifier = Modifier.clickable(onClick = onOpenPlugins).padding(horizontal = 3.dp),
                )
            }
            if (vm.automationMode) {
                val target = vm.automationTarget(track.id)
                MenuButton(vm.automationLabel(track.id, target), color = Color(0xFFFFD60A)) { close ->
                    vm.automatableTargets(track.id).forEach { (t, label) ->
                        val has = track.automation.any { it.target == t && it.points.isNotEmpty() }
                        DropdownMenuItem(text = { Text((if (has) "● " else "   ") + label) }, onClick = { vm.setAutomationTarget(track.id, t); close() })
                    }
                    DropdownMenuItem(text = { Text("이 오토메이션 지우기", color = DawColors.record) }, onClick = { vm.clearAutomation(track.id, target); close() })
                }
            } else if (height >= 76.dp) {
                MiniFader(track.volumeDb, onChange = { vm.setVolume(track.id, it) }, modifier = Modifier.fillMaxWidth().height(14.dp))
            }
            if (height >= 64.dp) TrackMeter(vm, track.id)
        }
    }
    if (renaming) {
        TextInputDialog("트랙 이름", track.name, onDismiss = { renaming = false }) { vm.renameTrack(track.id, it); renaming = false }
    }
    if (colorPicker) {
        ColorPickerDialog(track.color, onDismiss = { colorPicker = false }) { vm.setTrackColor(track.id, it); colorPicker = false }
    }
}

@Composable
private fun TrackMenu(
    vm: DawViewModel,
    track: Track,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onColor: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenTakes: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("이름 변경") }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text("색상") }, onClick = { onDismiss(); onColor() })
        DropdownMenuItem(text = { Text("Audio FX (플러그인)") }, onClick = { onDismiss(); onOpenPlugins() })
        DropdownMenuItem(text = { Text("트랙 복제") }, onClick = { onDismiss(); vm.duplicateTrack(track.id) })
        DropdownMenuItem(text = { Text("테이크 폴더") }, onClick = { onDismiss(); onOpenTakes() })
        DropdownMenuItem(text = { Text("서밍 스택 만들기") }, onClick = { onDismiss(); vm.createSummingStack(vm.selectedTrackIds() + track.id) })
        DropdownMenuItem(text = { Text(if (track.outputBusId != null) "스택에서 빼기 (Stereo Out)" else "출력: Stereo Out") }, onClick = { onDismiss(); vm.setTrackOutput(track.id, null) })
        DropdownMenuItem(text = { Text("그룹: " + (track.groupId?.let { "G$it → 다음" } ?: "없음 → G1")) }, onClick = {
            onDismiss(); vm.setGroup(track.id, when (val g = track.groupId) { null -> 1; 8 -> null; else -> g + 1 })
        })
        DropdownMenuItem(text = { Text("모든 리전 선택") }, onClick = { onDismiss(); vm.selectTrack(track.id); vm.selectAllOnTrack() })
        DropdownMenuItem(text = { Text("위로 이동") }, onClick = { onDismiss(); vm.moveTrack(track.id, -1) })
        DropdownMenuItem(text = { Text("아래로 이동") }, onClick = { onDismiss(); vm.moveTrack(track.id, 1) })
        DropdownMenuItem(text = { Text("트랙 삭제", color = DawColors.record) }, onClick = { onDismiss(); vm.deleteTrack(track.id) })
    }
}

/** Peak meter for one channel, isolated so meter ticks only recompose this composable. */
@Composable
fun TrackMeter(vm: DawViewModel, id: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val meters by vm.meters.collectAsState()
    HorizontalMeter(meters[id] ?: 0f, modifier)
}

/** Compact horizontal volume slider (Logic track header). Double-tap resets to 0 dB. */
@Composable
fun MiniFader(valueDb: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, minDb: Float = -60f, maxDb: Float = 6f) {
    val current by rememberUpdatedState(valueDb)
    val change by rememberUpdatedState(onChange)
    fun toPos(db: Float) = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { c, dx ->
                    c.consume()
                    val pos = toPos(current) + dx / size.width
                    change((minDb + pos.coerceIn(0f, 1f) * (maxDb - minDb)))
                }
            }
    ) {
        val cy = size.height / 2
        drawRoundRect(DawColors.knobTrack, Offset(0f, cy - 2f), Size(size.width, 4f), CornerRadius(2f, 2f))
        val x = toPos(valueDb) * size.width
        drawRoundRect(DawColors.accent.copy(alpha = 0.7f), Offset(0f, cy - 2f), Size(x, 4f), CornerRadius(2f, 2f))
        val zero = toPos(0f) * size.width
        drawLine(DawColors.textDim, Offset(zero, cy - 5f), Offset(zero, cy + 5f), strokeWidth = 1f)
        drawRoundRect(DawColors.faderCap, Offset((x - 5f).coerceIn(0f, size.width - 10f), 0f), Size(10f, size.height), CornerRadius(2f, 2f))
    }
}

@Composable
fun ColorPickerDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DawColors.trackColors.indices.forEach { c ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(DawColors.track(c))
                            .border(2.dp, if (c == current) Color.White else Color.Transparent, CircleShape)
                            .clickable { onPick(c) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
