package com.musiccut.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.musiccut.app.ui.components.MiniTransportBar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.musiccut.app.ui.BottomPane
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.ExportFormat
import com.musiccut.app.ui.ExportRange
import com.musiccut.app.ui.ExportTarget
import com.musiccut.app.ui.importLogicProject
import com.musiccut.app.ui.components.ArrangeView
import com.musiccut.app.ui.components.BusyDialog
import com.musiccut.app.ui.components.AudioTrackEditor
import com.musiccut.app.ui.components.FlexPitchSheet
import com.musiccut.app.ui.components.FlexTimeDialog
import com.musiccut.app.ui.components.MediaBrowserSheet
import com.musiccut.app.ui.components.TakesSheet
import com.musiccut.app.ui.components.ControlBar
import com.musiccut.app.ui.components.Inspector
import com.musiccut.app.ui.components.ProjectSettingsDialog
import com.musiccut.app.ui.components.QuickActions
import com.musiccut.app.ui.components.TracksMenuBar
import com.musiccut.app.ui.components.ExportDialog
import com.musiccut.app.ui.components.MixerView
import com.musiccut.app.ui.components.PluginBrowserDialog
import com.musiccut.app.ui.components.PluginChainSheet
import com.musiccut.app.ui.components.PluginWindow
import com.musiccut.app.ui.components.ProjectsDialog
import com.musiccut.app.ui.components.RegionInspectorSheet
import com.musiccut.app.ui.components.SeparationDialog
import com.musiccut.app.ui.components.TimelineState
import com.musiccut.app.ui.theme.DawColors
import java.io.File

private data class PendingExport(val format: ExportFormat, val range: ExportRange, val tail: Boolean, val loudness: com.musiccut.app.ui.LoudnessTarget)
private data class PluginRef(val ownerId: String, val slotId: String)

@Composable
fun DawScreen(
    vm: DawViewModel,
    driveToken: suspend () -> String,
    onShare: (File, String) -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val timeline = remember { TimelineState(vm.sampleRate) }
    var menuOpen by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showTempo by remember { mutableStateOf(false) }
    var showSeparation by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showFlexTime by remember { mutableStateOf(false) }
    var flexPitchRegion by remember { mutableStateOf<String?>(null) }
    var takesTrack by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var inspectRegion by remember { mutableStateOf<String?>(null) }
    var chainOwner by remember { mutableStateOf<String?>(null) }
    var browserOwner by remember { mutableStateOf<String?>(null) }
    var editPlugin by remember { mutableStateOf<PluginRef?>(null) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }

    val message by vm.messages.collectAsState()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importAudio(it) }
    }
    val logicImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importLogicProject(it) }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val p = pendingExport
        pendingExport = null
        if (uri != null && p != null) vm.export(p.format, p.range, ExportTarget.DEVICE, p.tail, p.loudness, deviceUri = uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { vm.openBrowserFolder(it) }
    }
    val openFlexPitch = { flexPitchRegion = vm.selectedRegions.singleOrNull().also { if (it == null) vm.toast("음정을 편집할 리전을 하나 선택하세요") } }
    val openTakes = { takesTrack = vm.selectedTrackId }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.toast("마이크 권한이 필요합니다")
    }

    fun onRecord() {
        if (vm.isRecording) {
            vm.stopRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording()
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun openImport() = importLauncher.launch(arrayOf("audio/*"))
    fun openLogicImport() = logicImportLauncher.launch(arrayOf("*/*"))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = DawColors.background,
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val shortScreen = maxHeight < 520.dp          // landscape phone
            val narrow = maxWidth < 600.dp                 // portrait phone
            val roomyForInspector = maxWidth >= 900.dp
            val paneHeight = (maxHeight * 0.45f).coerceIn(220.dp, 440.dp)
            // re-evaluated whenever the device rotates
            LaunchedEffect(landscape, shortScreen, roomyForInspector) {
                vm.headerLevel = if (shortScreen) 1 else 0
                if (!roomyForInspector) vm.showInspector = false
            }
            val paneReplacesTracks = shortScreen && vm.bottomPane != BottomPane.NONE
            val menuContent: @Composable () -> Unit = {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("프로젝트 열기 / 저장") }, onClick = { menuOpen = false; vm.refreshProjects(); showProjects = true })
                            DropdownMenuItem(text = { Text("빠른 저장") }, onClick = { menuOpen = false; vm.save() })
                            DropdownMenuItem(text = { Text("오디오 가져오기") }, onClick = { menuOpen = false; openImport() })
                            DropdownMenuItem(text = { Text("Logic 프로젝트 가져오기 (.logicx / .zip)") }, onClick = { menuOpen = false; openLogicImport() })
                            DropdownMenuItem(text = { Text("내보내기 (Bounce) / Drive 업로드") }, onClick = { menuOpen = false; showExport = true })
                            DropdownMenuItem(text = { Text("프로젝트를 Drive에 백업") }, onClick = { menuOpen = false; vm.backupProjectToDrive(driveToken) })
                            DropdownMenuItem(text = { Text("Stereo Out 플러그인") }, onClick = { menuOpen = false; chainOwner = com.musiccut.app.ui.MASTER_ID })
                            DropdownMenuItem(text = { Text("새 Aux 채널 (리버브)") }, onClick = { menuOpen = false; vm.addBus("chromaverb"); vm.bottomPane = BottomPane.MIXER })
                            DropdownMenuItem(text = { Text("템포 · 박자 · 키") }, onClick = { menuOpen = false; showTempo = true })
                        DropdownMenuItem(text = { Text("루프 · 미디어 브라우저") }, onClick = { menuOpen = false; showBrowser = true })
                        DropdownMenuItem(text = { Text((if (vm.autosave) "✓ " else "   ") + "자동 저장 (1분마다)") }, onClick = { menuOpen = false; vm.autosave = !vm.autosave })
                            DropdownMenuItem(text = { Text("사용법") }, onClick = { menuOpen = false; showHelp = true })
                        }
            }

            Column(Modifier.fillMaxSize()) {
                Box {
                    when (vm.headerLevel) {
                        2 -> MiniTransportBar(vm, onMenu = { menuOpen = true }, onRecord = ::onRecord)
                        else -> ControlBar(
                            vm,
                            onMenu = { menuOpen = true },
                            onRecord = ::onRecord,
                            onProjectSettings = { showTempo = true },
                            twoRows = narrow && !landscape,
                            compactLcd = shortScreen,
                        )
                    }
                    menuContent()
                }
                if (vm.headerLevel == 0) {
                    TracksMenuBar(
                        vm, timeline,
                        onImport = ::openImport,
                        onSeparate = { showSeparation = true },
                        onInspect = { inspectRegion = vm.selectedRegions.singleOrNull() },
                        onFlexTime = { showFlexTime = true },
                        onFlexPitch = { openFlexPitch() },
                        onTakes = { openTakes() },
                        onBrowser = { showBrowser = true },
                    )
                }
                HeaderHandle(vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.fillMaxSize()) {
                        if (vm.showInspector && roomyForInspector) {
                            Inspector(
                                vm,
                                onEditPlugin = { owner, slot -> editPlugin = PluginRef(owner, slot) },
                                onAddPlugin = { browserOwner = it },
                                onSeparate = { showSeparation = true },
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            if (!paneReplacesTracks) {
                                ArrangeView(
                                    vm = vm,
                                    timeline = timeline,
                                    onOpenRegion = { inspectRegion = it },
                                    onOpenTrackPlugins = { chainOwner = it },
                                    modifier = Modifier.weight(1f),
                                    showGlobalTracks = vm.headerLevel == 0,
                                    onOpenTakes = { takesTrack = it },
                                )
                                if (!landscape) {
                                    QuickActions(
                                        vm = vm,
                                        onImport = ::openImport,
                                        onSeparate = { showSeparation = true },
                                        onInspect = { inspectRegion = vm.selectedRegions.singleOrNull() },
                                        onFlexTime = { showFlexTime = true },
                                        onBrowser = { showBrowser = true },
                                    )
                                }
                            }
                            val paneModifier = if (paneReplacesTracks) Modifier.weight(1f) else Modifier.height(paneHeight)
                            when (vm.bottomPane) {
                                BottomPane.MIXER -> MixerView(
                                    vm = vm,
                                    onEditPlugin = { owner, slot -> editPlugin = PluginRef(owner, slot) },
                                    onAddPlugin = { browserOwner = it },
                                    modifier = paneModifier.fillMaxWidth(),
                                )
                                BottomPane.EDITOR -> AudioTrackEditor(
                                    vm = vm,
                                    onOpenRegion = { inspectRegion = it },
                                    modifier = paneModifier,
                                )
                                BottomPane.NONE -> Unit
                            }
                        }
                        if (landscape && !paneReplacesTracks) {
                            QuickActions(
                                vm = vm,
                                onImport = ::openImport,
                                onSeparate = { showSeparation = true },
                                onInspect = { inspectRegion = vm.selectedRegions.singleOrNull() },
                                vertical = true,
                                onFlexTime = { showFlexTime = true },
                                onBrowser = { showBrowser = true },
                            )
                        }
                    }
                    // On smaller screens the inspector slides over the tracks instead of squeezing them.
                    if (vm.showInspector && !roomyForInspector) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { vm.showInspector = false })
                        Inspector(
                            vm,
                            onEditPlugin = { owner, slot -> editPlugin = PluginRef(owner, slot) },
                            onAddPlugin = { browserOwner = it },
                            onSeparate = { showSeparation = true },
                            modifier = Modifier.shadow(12.dp),
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- sheets & dialogs
    inspectRegion?.let { id ->
        RegionInspectorSheet(vm, id, onDismiss = { inspectRegion = null }, onSeparate = {
            inspectRegion = null
            showSeparation = true
        })
    }
    chainOwner?.let { owner ->
        PluginChainSheet(
            vm, owner,
            onDismiss = { chainOwner = null },
            onEdit = { slot -> chainOwner = null; editPlugin = PluginRef(owner, slot) },
            onAdd = { chainOwner = null; browserOwner = owner },
        )
    }
    browserOwner?.let { owner ->
        PluginBrowserDialog(onDismiss = { browserOwner = null }) { d ->
            browserOwner = null
            vm.addPlugin(owner, d.id)?.let { editPlugin = PluginRef(owner, it) }
        }
    }
    editPlugin?.let { ref ->
        PluginWindow(vm, ref.ownerId, ref.slotId, onDismiss = { editPlugin = null })
    }
    if (showProjects) ProjectsDialog(vm, onDismiss = { showProjects = false })
    if (showTempo) ProjectSettingsDialog(vm, onDismiss = { showTempo = false })
    if (showSeparation) {
        SeparationDialog(vm, onDismiss = { showSeparation = false }) { options, mute, engine ->
            showSeparation = false
            vm.separateSelected(options, mute, engine)
        }
    }
    if (showExport) {
        ExportDialog(
            hasCycle = vm.project.cycleEnd > vm.project.cycleStart,
            hasRange = vm.range != null,
            onDismiss = { showExport = false },
        ) { format, range, target, tail, loudness ->
            showExport = false
            when (target) {
                ExportTarget.DEVICE -> {
                    pendingExport = PendingExport(format, range, tail, loudness)
                    saveLauncher.launch(vm.suggestedExportName(format))
                }
                ExportTarget.DRIVE -> vm.export(format, range, target, tail, loudness, driveToken = driveToken)
                ExportTarget.SHARE -> vm.export(format, range, target, tail, loudness, onShare = onShare)
            }
        }
    }
    if (showHelp) HelpDialog { showHelp = false }
    if (showFlexTime) FlexTimeDialog(vm) { showFlexTime = false }
    flexPitchRegion?.let { id -> FlexPitchSheet(vm, id) { flexPitchRegion = null } }
    takesTrack?.let { id -> TakesSheet(vm, id) { takesTrack = null } }
    if (showBrowser) MediaBrowserSheet(vm, onPickFolder = { folderLauncher.launch(null) }) { showBrowser = false }
    vm.busy?.let { BusyDialog(it, onCancel = vm::cancelBusy) }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사용법") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    """
                    ■ 화면 구성 (로직과 동일)
                    • 상단 컨트롤 바: 인스펙터(i) · 믹서 · 에디터 토글, 트랜스포트, LCD(마디·시간·템포·박자·키 — 탭하면 설정), 카운트인(1234), 메트로놈, 출력 미터
                    • 왼쪽 인스펙터: 선택한 리전의 게인/페이드/뮤트/리버스, 트랙 채널 스트립 + Stereo Out
                    • 트랙 영역 위 메뉴: 편집 · 기능 · 탐색 · 보기, 왼쪽 클릭 도구, 스냅, 줌 슬라이더
                    • 하단: 믹서(채널 스트립, 센드, Aux) 또는 오디오 트랙 에디터(선택 리전 확대 편집)

                    ■ 편집 도구
                    • 포인터: 탭=선택, 길게=다중 선택, 드래그=이동(다른 트랙으로도), 리전 아래 양 끝=길이 조절, 위 양 끝=페이드, 두 번 탭=리전 설정
                    • 범위(마퀴): 드래그해서 시간 구간 선택 · 가위: 탭한 곳에서 자르기
                    • 두 손가락 핀치: 확대/축소 · 빈 곳 드래그: 스크롤
                    • 눈금자 위쪽 노란 띠 드래그: 사이클(반복) 구간 지정 · 아래쪽: 재생 위치 이동

                    ■ 노래 자르고 붙이기
                    1) 아레인지먼트 트랙에 Intro / Verse / Chorus 섹션을 만들고(+ 버튼, 또는 범위 선택 후 '섹션 만들기') 섹션을 길게 눌러 삭제·복제·순서 바꾸기 — 곡 구조를 통째로 편집
                    2) 범위 도구로 구간 선택 → '삭제+당기기' (뒷부분이 자동으로 붙음)
                    3) 복사 → 플레이헤드 이동 → '끼워넣기'(뒤를 밀어냄) 또는 '붙여넣기'(덮어쓰기)
                    4) 넛지 ◀ ▶ 로 그리드 한 칸씩 미세 이동, 스냅 끄면 10ms 단위

                    ■ 상단 핸들: 위로 밀면 상단 영역이 접히고(3단계) 아래로 당기면 펼쳐집니다
                    ■ 오토메이션: 'A' 버튼 → 트랙 헤더에서 파라미터 선택 → 탭=점 추가, 드래그=이동, 길게=삭제. 믹스 메뉴의 Latch 쓰기를 켜면 재생 중 페이더 움직임이 기록됩니다
                    ■ AI 보컬 분리: 처음 한 번 모델(약 64MB)을 받은 뒤 오프라인으로 동작
                    ■ Flex: 기능 메뉴 → 속도/음정/템포, Flex Pitch(음표 음정 편집), 템포 감지
                    ■ 테이크: 같은 구간을 다시 녹음하면 트랙 헤더에 T 표시 → 좋은 테이크/구간을 골라 컴핑
                    ■ 믹서: 채널 EQ 썸네일을 탭하면 Channel EQ가 열림, Send 슬롯에서 새 Aux(리버브/딜레이) 생성, Output 슬롯으로 스택 라우팅, Group 슬롯으로 그룹
                    ■ 컴프레서/게이트 창의 Side Chain에서 다른 트랙을 키로 지정 (킥 덕킹)
                    ■ 내보내기: MP3/WAV/M4A, LUFS 맞춤(-14 등), 결과 음량 표시 · 자동 저장 1분마다
                    ■ 플러그인: Channel EQ는 그래프에서 밴드를 직접 드래그, 재생 중 스펙트럼 분석기 표시. 컴프레서는 게인 리덕션 미터와 전달 곡선 표시
                    ■ 녹음: 트랙의 R 버튼 → 녹음. 1234가 켜져 있으면 한 마디 카운트인
                    ■ Google Drive: 메뉴 → 내보내기 / Drive 업로드
                    """.trimIndent(),
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
    )
}

/**
 * Grip below the header: drag up to collapse (full → control bar only → one-line transport),
 * drag down to expand again; a tap cycles through the levels.
 */
@Composable
private fun HeaderHandle(vm: DawViewModel) {
    val vmRef by rememberUpdatedState(vm)
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(DawColors.panel)
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        acc += dy
                        val step = 28.dp.toPx()
                        if (acc < -step) { vmRef.headerLevel = (vmRef.headerLevel + 1).coerceAtMost(2); acc = 0f }
                        if (acc > step) { vmRef.headerLevel = (vmRef.headerLevel - 1).coerceAtLeast(0); acc = 0f }
                    },
                )
            }
            .clickable { vm.headerLevel = if (vm.headerLevel >= 2) 0 else vm.headerLevel + 1 },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(DawColors.textDim))
            Icon(
                if (vm.headerLevel >= 2) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                if (vm.headerLevel >= 2) "상단 펼치기" else "상단 접기",
                tint = DawColors.textDim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
