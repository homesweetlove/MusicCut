package com.musiccut.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.musiccut.app.ui.BusyState
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.ExportFormat
import com.musiccut.app.ui.ExportRange
import com.musiccut.app.ui.ExportTarget
import com.musiccut.app.ui.LoudnessTarget
import com.musiccut.app.ui.SeparationEngine
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.project.TimeUtil
import com.musiccut.core.separation.SeparationOptions
import com.musiccut.core.separation.SeparationQuality
import java.text.DateFormat
import java.util.Date

@Composable
fun BusyDialog(state: BusyState, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(state.message, fontSize = 16.sp) },
        text = {
            Column {
                val p = state.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()}%", fontSize = 12.sp, color = DawColors.textDim, modifier = Modifier.padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (state.cancellable) TextButton(onClick = onCancel) { Text("취소") }
        },
    )
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
fun SeparationDialog(vm: DawViewModel, onDismiss: () -> Unit, onRun: (SeparationOptions, Boolean, SeparationEngine) -> Unit) {
    var engine by remember { mutableStateOf(SeparationEngine.AI_VOC_FT) }
    var quality by remember { mutableStateOf(SeparationQuality.BALANCED) }
    var strength by remember { mutableFloatStateOf(0.6f) }
    var perc by remember { mutableFloatStateOf(0.5f) }
    var muteOriginal by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 스템 분리") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (engine == SeparationEngine.AI_DEMUCS_6S)
                        "선택한 리전을 Vocals · Drums · Bass · Guitar · Piano · Other 6개 트랙으로 나눕니다."
                    else "선택한 리전을 보컬과 반주(Instrumental) 두 트랙으로 나눕니다.",
                    fontSize = 12.sp, color = DawColors.textDim,
                )
                Spacer(Modifier.height(8.dp))
                Text("분리 엔진", fontWeight = FontWeight.SemiBold)
                SeparationEngine.entries.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { engine = e }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = engine == e, onClick = { engine = e })
                        Column {
                            Text(e.label, fontSize = 13.sp)
                            val modelMb = vm.separationModelSizeMb(e)
                            if (modelMb != null) {
                                Text(
                                    if (vm.isSeparationModelInstalled(e)) "설치됨 · 오프라인 사용 가능" else "처음 사용 시 모델 다운로드 (약 ${modelMb}MB, Wi-Fi 권장)",
                                    fontSize = 11.sp, color = DawColors.textDim,
                                )
                            }
                        }
                    }
                }
                if (engine == SeparationEngine.DSP) {
                    Text("품질", fontWeight = FontWeight.SemiBold)
                    ChoiceRow(SeparationQuality.entries, quality, { it.label }) { quality = it }
                    Text("분리 강도 ${(strength * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                    Slider(value = strength, onValueChange = { strength = it })
                    Text("드럼 억제 ${(perc * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                    Slider(value = perc, onValueChange = { perc = it })
                } else {
                    if (engine == SeparationEngine.AI_DEMUCS_6S) {
                        Text("6-Stem은 136MB 모델을 사용하며 처리 중 메모리 사용량이 큽니다. 결과는 각각 독립 트랙으로 편집할 수 있습니다.", fontSize = 11.sp, color = DawColors.textDim)
                    } else {
                        Text("AI 분리는 곡 1분당 폰 성능에 따라 약 30초~2분 걸립니다.", fontSize = 11.sp, color = DawColors.textDim)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { muteOriginal = !muteOriginal }) {
                    Checkbox(checked = muteOriginal, onCheckedChange = { muteOriginal = it })
                    Text("원본 리전 뮤트")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRun(SeparationOptions(quality = quality, strength = strength, percussionSuppression = perc), muteOriginal, engine) }) { Text("분리 시작") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ExportDialog(
    hasCycle: Boolean,
    hasRange: Boolean,
    onDismiss: () -> Unit,
    onExport: (ExportFormat, ExportRange, ExportTarget, Boolean, LoudnessTarget) -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.MP3_320) }
    var loudness by remember { mutableStateOf(LoudnessTarget.OFF) }
    var range by remember { mutableStateOf(ExportRange.SONG) }
    var target by remember { mutableStateOf(ExportTarget.DRIVE) }
    var tail by remember { mutableStateOf(true) }
    val ranges = buildList {
        add(ExportRange.SONG)
        if (hasCycle) add(ExportRange.CYCLE)
        if (hasRange) add(ExportRange.SELECTION)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("믹스 내보내기 (Bounce)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("포맷", fontWeight = FontWeight.SemiBold)
                ChoiceRow(ExportFormat.entries, format, { it.label }) { format = it }
                Text("범위", fontWeight = FontWeight.SemiBold)
                ChoiceRow(ranges, range, { it.label }) { range = it }
                Text("음량 맞춤 (LUFS 노멀라이즈)", fontWeight = FontWeight.SemiBold)
                ChoiceRow(LoudnessTarget.entries, loudness, { it.label }) { loudness = it }
                Text("보낼 곳", fontWeight = FontWeight.SemiBold)
                ChoiceRow(ExportTarget.entries, target, { it.label }) { target = it }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { tail = !tail }) {
                    Checkbox(checked = tail, onCheckedChange = { tail = it })
                    Text("리버브/딜레이 꼬리 포함 (+2초)")
                }
                if (target == ExportTarget.DRIVE) {
                    Text("Google Drive의 'MusicCut AI' 폴더에 업로드됩니다.", fontSize = 12.sp, color = DawColors.textDim)
                }
            }
        },
        confirmButton = { Button(onClick = { onExport(format, range, target, tail, loudness) }) { Text("내보내기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ProjectsDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    var saveAs by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프로젝트") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.newProject(); onDismiss() }) { Text("새로 만들기") }
                    OutlinedButton(onClick = { vm.save(); onDismiss() }) { Text("저장") }
                    OutlinedButton(onClick = { saveAs = true }) { Text("다른 이름") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (vm.savedProjects.isEmpty()) Text("저장된 프로젝트가 없습니다", color = DawColors.textDim)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(vm.savedProjects, key = { it.name }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.open(p.name); onDismiss() }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 15.sp)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.modified)), fontSize = 11.sp, color = DawColors.textDim)
                            }
                            IconButton(onClick = { confirmDelete = p.name }) { Icon(Icons.Filled.Delete, "삭제") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    if (saveAs) {
        TextInputDialog("프로젝트 이름", vm.project.name, onDismiss = { saveAs = false }) {
            vm.save(it)
            saveAs = false
            onDismiss()
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("'$name' 삭제?") },
            text = { Text("프로젝트와 오디오 파일이 영구 삭제됩니다.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(name); confirmDelete = null }) { Text("삭제", color = DawColors.record) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}

private val KEYS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

/** Project settings opened from the LCD: tempo, time signature and key. */
@Composable
fun ProjectSettingsDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    val project = vm.project
    var tempo by remember { mutableStateOf("%.1f".format(project.tempo)) }
    var beats by remember { mutableStateOf(project.beatsPerBar) }
    var key by remember { mutableStateOf(project.keySignature.substringBefore(' ')) }
    var minor by remember { mutableStateOf(project.keySignature.endsWith("minor")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("템포 · 박자 · 키") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = tempo, onValueChange = { tempo = it }, singleLine = true, label = { Text("템포 (BPM)") })
                Text("박자", fontWeight = FontWeight.SemiBold)
                ChoiceRow((2..7).toList(), beats, { "$it/4" }) { beats = it }
                Text("키", fontWeight = FontWeight.SemiBold)
                ChoiceRow(KEYS, key, { it }) { key = it }
                ChoiceRow(listOf(false, true), minor, { if (it) "minor" else "major" }) { minor = it }
                Text("템포와 박자는 스냅 그리드, 마디 표시, 메트로놈에 사용됩니다.", fontSize = 12.sp, color = DawColors.textDim)
            }
        },
        confirmButton = {
            val v = tempo.replace(',', '.').toFloatOrNull()
            TextButton(
                onClick = {
                    if (v != null) vm.setTempo(v)
                    if (beats != project.beatsPerBar) vm.setTimeSignature(beats)
                    val k = "$key ${if (minor) "minor" else "major"}"
                    if (k != project.keySignature) vm.setKey(k)
                    onDismiss()
                },
                enabled = v != null && v in 30f..300f,
            ) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Region inspector: name, gain, fades, and quick region actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionInspectorSheet(vm: DawViewModel, regionId: String, onDismiss: () -> Unit, onSeparate: () -> Unit) {
    val found = vm.project.findRegion(regionId)
    val before = remember(regionId) { vm.project }
    DisposableEffect(regionId) {
        onDispose { vm.commitRegionChange("리전 설정", before) }
    }
    if (found == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val region = found.second
    val sr = vm.project.sampleRate
    var renaming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(region.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).clickable { renaming = true })
                TextButton(onClick = { renaming = true }) { Text("이름 변경") }
            }
            Text(
                "시작 ${TimeUtil.barsBeats(region.start, vm.project)} (${TimeUtil.clock(region.start, sr)}) · 길이 ${"%.2f".format(region.length.toFloat() / sr)}초",
                fontSize = 12.sp, color = DawColors.textDim,
            )
            Spacer(Modifier.height(10.dp))
            Text("게인 ${"%.1f".format(region.gainDb)} dB")
            Slider(value = region.gainDb, onValueChange = { vm.setRegionGain(regionId, it) }, valueRange = -24f..12f)
            val maxFade = minOf(region.length, (sr * 10).toLong()).toFloat()
            Text("페이드 인 ${"%.2f".format(region.fadeIn.toFloat() / sr)}초")
            Slider(value = region.fadeIn.toFloat().coerceAtMost(maxFade), onValueChange = { vm.setRegionFades(regionId, it.toLong(), null) }, valueRange = 0f..maxFade)
            Text("페이드 아웃 ${"%.2f".format(region.fadeOut.toFloat() / sr)}초")
            Slider(value = region.fadeOut.toFloat().coerceAtMost(maxFade), onValueChange = { vm.setRegionFades(regionId, null, it.toLong()) }, valueRange = 0f..maxFade)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::splitAtPlayhead) { Text("플레이헤드에서 자르기") }
                OutlinedButton(onClick = vm::normalizeSelection) { Text("노멀라이즈") }
                OutlinedButton(onClick = vm::reverseSelection) { Text(if (region.reversed) "리버스 해제" else "리버스") }
                OutlinedButton(onClick = vm::toggleMuteSelection) { Text(if (region.muted) "뮤트 해제" else "뮤트") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSeparate) { Text("보컬 / 반주 분리") }
                TextButton(onClick = { vm.deleteSelection(); onDismiss() }) { Text("삭제", color = DawColors.record) }
            }
        }
    }
    if (renaming) {
        TextInputDialog("리전 이름", region.name, onDismiss = { renaming = false }) { vm.renameRegion(regionId, it); renaming = false }
    }
}
