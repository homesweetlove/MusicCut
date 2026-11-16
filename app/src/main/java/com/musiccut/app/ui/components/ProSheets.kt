package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.dsp.PitchDetector
import com.musiccut.core.dsp.PitchNote
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------- takes

/** Logic take folder: every take of a track, with comping (whole take or the selected range). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakesSheet(vm: DawViewModel, trackId: String, onDismiss: () -> Unit) {
    val track = vm.project.track(trackId)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = DawColors.panel) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("테이크 폴더 · ${track?.name ?: ""}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "녹음을 겹쳐 할 때마다 테이크가 쌓입니다. 원하는 테이크를 통째로 쓰거나, 범위 도구로 구간을 고른 뒤 '선택 구간만'을 눌러 좋은 부분만 모아 완성본(컴프)을 만드세요.",
                fontSize = 12.sp, color = DawColors.textDim,
            )
            Spacer(Modifier.height(8.dp))
            if (track == null || track.takeLanes.isEmpty()) {
                Text("아직 테이크가 없습니다. 같은 구간을 한 번 더 녹음하면 테이크 폴더가 생깁니다.", color = DawColors.textDim)
            } else {
                track.takeLanes.forEach { lane ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(DawColors.surface).padding(8.dp)) {
                        Text(lane.name, fontWeight = FontWeight.SemiBold)
                        TakeWaveform(vm, lane.regions, Modifier.fillMaxWidth().height(40.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { vm.compTake(trackId, lane.id, useRange = false) }) { Text("이 테이크 사용") }
                            OutlinedButton(onClick = { vm.compTake(trackId, lane.id, useRange = true) }, enabled = vm.range != null) { Text("선택 구간만") }
                            TextButton(onClick = { vm.deleteTake(trackId, lane.id) }) { Text("삭제", color = DawColors.record) }
                        }
                    }
                }
                TextButton(onClick = { vm.flattenTakes(trackId); onDismiss() }) { Text("테이크 정리 (컴프만 남기기)") }
            }
        }
    }
}

@Composable
private fun TakeWaveform(vm: DawViewModel, regions: List<com.musiccut.core.project.Region>, modifier: Modifier) {
    val end = vm.project.contentEnd.coerceAtLeast(regions.maxOfOrNull { it.end } ?: 1)
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(DawColors.lcdBackground)) {
        val w = size.width
        val cy = size.height / 2
        for (r in regions) {
            val x0 = r.start.toFloat() / end * w
            val x1 = r.end.toFloat() / end * w
            drawRect(DawColors.track(r.color).copy(alpha = 0.5f), Offset(x0, 0f), Size(x1 - x0, size.height))
            val peaks = vm.pool.peaks(r.sourceId) ?: continue
            val steps = (x1 - x0).toInt().coerceAtLeast(1)
            for (i in 0 until steps) {
                val src = r.sourceOffset + (i.toFloat() / steps * r.length).toLong()
                val k = (src / peaks.samplesPerPeak).toInt().coerceIn(0, peaks.size - 1)
                drawLine(Color.White.copy(alpha = 0.7f), Offset(x0 + i, cy - peaks.max[k] * cy), Offset(x0 + i, cy - peaks.min[k] * cy))
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Flex Time

/** Flex Time / tempo tools for the selected region. */
@Composable
fun FlexTimeDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    var percent by remember { mutableFloatStateOf(100f) }
    var varispeed by remember { mutableStateOf(false) }
    var semis by remember { mutableFloatStateOf(0f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flex · 속도 / 음정 / 템포") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("길이 (속도) ${"%.0f".format(percent)}%  → ${if (percent > 100) "느리게" else if (percent < 100) "빠르게" else "원래대로"}", fontWeight = FontWeight.SemiBold)
                Slider(value = percent, onValueChange = { percent = it.roundToInt().toFloat() }, valueRange = 50f..200f)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { varispeed = !varispeed }) {
                    Checkbox(checked = varispeed, onCheckedChange = { varispeed = it })
                    Text("Varispeed (테이프처럼 음정도 같이 변함)")
                }
                Button(onClick = { vm.stretchSelection(percent / 100.0, varispeed); onDismiss() }, enabled = percent != 100f) { Text("길이 적용") }
                HorizontalDivider()
                Text("음정 이동 (길이 유지) ${"%+.1f".format(semis)} 반음", fontWeight = FontWeight.SemiBold)
                Slider(value = semis, onValueChange = { semis = (it * 2).roundToInt() / 2f }, valueRange = -12f..12f)
                Button(onClick = { vm.transposeSelection(semis.toDouble()); onDismiss() }, enabled = semis != 0f) { Text("음정 적용") }
                HorizontalDivider()
                Text("템포", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(onClick = { vm.detectTempo(apply = false); onDismiss() }) { Text("템포 감지") }
                    OutlinedButton(onClick = { vm.detectTempo(apply = true); onDismiss() }) { Text("감지 → 프로젝트 템포") }
                    OutlinedButton(onClick = { vm.matchRegionToProjectTempo(); onDismiss() }) { Text("리전을 프로젝트 템포에 맞춤") }
                }
                Text("리전을 선택하지 않으면 템포 감지는 곡 앞부분 60초를 분석합니다.", fontSize = 11.sp, color = DawColors.textDim)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

// ---------------------------------------------------------------------------------- Flex Pitch

/**
 * Flex Pitch editor: detected notes of the selected (monophonic) region as blobs on a piano roll.
 * Drag a note up / down to change its pitch, or snap all notes to the key; "적용" renders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlexPitchSheet(vm: DawViewModel, regionId: String, onDismiss: () -> Unit) {
    var notes by remember { mutableStateOf<List<PitchNote>?>(null) }
    val shifts = remember { mutableStateMapOf<Int, Float>() }
    var selected by remember { mutableStateOf(-1) }
    LaunchedEffect(regionId) { notes = vm.analyzeNotes(regionId) }
    val measurer = rememberTextMeasurer()
    val keyRoot = PitchDetector.NOTE_NAMES.indexOf(vm.project.keySignature.substringBefore(' ')).coerceAtLeast(0)
    val scale = if (vm.project.keySignature.endsWith("minor")) PitchDetector.SCALES.getValue("Minor") else PitchDetector.SCALES.getValue("Major")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = DawColors.panel) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Text("Flex Pitch", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("음표를 위아래로 드래그해 음정을 바꾸고 '적용'을 누르세요. 단선율(보컬·악기 한 줄)에서 동작합니다.", fontSize = 12.sp, color = DawColors.textDim)
            Spacer(Modifier.height(8.dp))
            val list = notes
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (list.isEmpty()) {
                Text("음정을 찾지 못했습니다. 보컬 한 줄만 있는 리전(예: 보컬 분리 결과)을 선택하세요.", color = DawColors.textDim)
            } else {
                val lo = (list.minOf { it.midi } - 4).roundToInt()
                val hi = (list.maxOf { it.midi } + 4).roundToInt()
                val end = list.maxOf { it.end }.toFloat()
                val shiftsRef by rememberUpdatedState(shifts)
                Canvas(
                    Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(6.dp)).background(DawColors.lcdBackground)
                        .pointerInput(list) {
                            detectTapGestures { o ->
                                val rowH = size.height.toFloat() / (hi - lo + 1)
                                selected = list.indexOfFirst { n ->
                                    val x0 = n.start / end * size.width; val x1 = n.end / end * size.width
                                    val m = n.midi + (shiftsRef[list.indexOf(n)] ?: 0f)
                                    val y = (hi - m) * rowH
                                    o.x in x0..x1 && kotlin.math.abs(o.y - y) < rowH * 1.5f
                                }
                            }
                        }
                        .pointerInput(list) {
                            var acc = 0f
                            detectVerticalDragGestures(
                                onDragStart = { o ->
                                    acc = 0f
                                    val rowH = size.height.toFloat() / (hi - lo + 1)
                                    selected = list.indexOfFirst { n ->
                                        val x0 = n.start / end * size.width; val x1 = n.end / end * size.width
                                        o.x in x0..x1
                                    }
                                    if (selected >= 0) acc = -(shiftsRef[selected] ?: 0f) * rowH
                                },
                                onVerticalDrag = { c, dy ->
                                    c.consume()
                                    if (selected >= 0) {
                                        acc += dy
                                        val rowH = size.height.toFloat() / (hi - lo + 1)
                                        shifts[selected] = (-acc / rowH).roundToInt().toFloat()
                                    }
                                },
                            )
                        }
                ) {
                    val rowH = size.height / (hi - lo + 1)
                    for (m in lo..hi) {
                        val y = (hi - m) * rowH
                        val black = m % 12 in listOf(1, 3, 6, 8, 10)
                        drawRect(if (black) Color(0xFF15161A) else Color(0xFF1C1D22), Offset(0f, y), Size(size.width, rowH))
                        if (m % 12 == 0) drawText(measurer, PitchDetector.noteName(m.toFloat()), Offset(2f, y), TextStyle(color = DawColors.lcdLabel, fontSize = 8.sp))
                    }
                    list.forEachIndexed { i, n ->
                        val x0 = n.start / end * size.width
                        val x1 = n.end / end * size.width
                        val shift = shifts[i] ?: 0f
                        val origY = (hi - n.midi) * rowH
                        val y = (hi - (n.midi + shift)) * rowH
                        if (shift != 0f) drawRoundRect(Color.White.copy(alpha = 0.15f), Offset(x0, origY - rowH / 2), Size(x1 - x0, rowH), CornerRadius(4f, 4f))
                        drawRoundRect(if (i == selected) DawColors.cycle else DawColors.accent, Offset(x0, y - rowH / 2), Size((x1 - x0).coerceAtLeast(3f), rowH), CornerRadius(4f, 4f))
                        if (x1 - x0 > 24) drawText(measurer, PitchDetector.noteName(n.midi + shift), Offset(x0 + 2f, y - rowH / 2), TextStyle(color = Color.Black, fontSize = 8.sp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        list.forEachIndexed { i, n -> shifts[i] = PitchDetector.snap(n.midi, keyRoot, scale) - n.midi }
                    }) { Text("키에 맞추기 (${vm.project.keySignature})") }
                    OutlinedButton(onClick = {
                        list.forEachIndexed { i, n -> shifts[i] = kotlin.math.round(n.midi) - n.midi }
                    }) { Text("가장 가까운 반음") }
                    OutlinedButton(onClick = { shifts.clear() }) { Text("초기화") }
                    Button(onClick = { vm.applyNoteShifts(list, shifts.toMap()); onDismiss() }, enabled = shifts.values.any { it != 0f }) { Text("적용") }
                }
                if (selected in list.indices) {
                    val n = list[selected]
                    Text(
                        "선택: ${PitchDetector.noteName(n.midi)} (${"%+.0f".format((n.midi - kotlin.math.round(n.midi)) * 100)} cents) → ${"%+.1f".format(shifts[selected] ?: 0f)} 반음",
                        fontSize = 12.sp, color = DawColors.textDim,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- browser

/** Loop / media browser: pick a folder, preview files, drop them at the playhead (optionally tempo-matched). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowserSheet(vm: DawViewModel, onPickFolder: () -> Unit, onDismiss: () -> Unit) {
    var matchTempo by remember { mutableStateOf(true) }
    ModalBottomSheet(
        onDismissRequest = { vm.stopPreview(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DawColors.panel,
    ) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("루프 · 미디어 브라우저", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onPickFolder) { Icon(Icons.Filled.FolderOpen, "폴더 선택") }
            }
            if (vm.browserTree == null) {
                Text("루프나 샘플이 들어 있는 폴더를 선택하세요 (다운로드 폴더 등).", color = DawColors.textDim)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPickFolder) { Text("폴더 선택") }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { matchTempo = !matchTempo }) {
                Checkbox(checked = matchTempo, onCheckedChange = { matchTempo = it })
                Text("넣을 때 프로젝트 템포(${"%.0f".format(vm.project.tempo)} BPM)에 맞추기", fontSize = 13.sp)
            }
            if (vm.browserPath.isNotEmpty()) {
                Row(Modifier.clickable { vm.browseUp() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                    Text("  ${vm.browserPath.joinToString(" / ") { it.second }}", fontSize = 12.sp, color = DawColors.textDim)
                }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(vm.browserItems, key = { it.uri.toString() }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (item.isDir) vm.browseInto(item) else vm.togglePreview(item) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (item.isDir) Icons.Filled.Folder else Icons.Filled.MusicNote, null, tint = if (item.isDir) DawColors.cycle else DawColors.accent)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(item.name, fontSize = 14.sp, maxLines = 1)
                            if (!item.isDir) Text("%.1f MB".format(item.size / 1_048_576f), fontSize = 10.sp, color = DawColors.textDim)
                        }
                        if (!item.isDir) {
                            IconButton(onClick = { vm.togglePreview(item) }) {
                                Icon(if (vm.previewing == item.uri) Icons.Filled.Stop else Icons.Filled.PlayArrow, "미리듣기")
                            }
                            IconButton(onClick = { vm.stopPreview(); vm.importAudio(item.uri, matchTempo); onDismiss() }) {
                                Icon(Icons.Filled.Add, "프로젝트에 넣기")
                            }
                        }
                    }
                }
            }
        }
    }
}
