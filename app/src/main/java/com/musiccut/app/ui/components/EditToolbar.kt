package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ControlPointDuplicate
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.Tool
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.project.GridSize
import kotlin.math.ln

/**
 * Logic's tracks-area menu bar: Edit / Functions / View / Navigate menus, the left-click tool,
 * snap setting and the horizontal zoom slider.
 */
@Composable
fun TracksMenuBar(
    vm: DawViewModel,
    timeline: TimelineState,
    onImport: () -> Unit,
    onSeparate: () -> Unit,
    onInspect: () -> Unit,
    onFlexTime: () -> Unit = {},
    onFlexPitch: () -> Unit = {},
    onTakes: () -> Unit = {},
    onBrowser: () -> Unit = {},
) {
    val hasSel = vm.selectedRegions.isNotEmpty()
    val hasRange = vm.range != null
    val hasClip = vm.clipboard?.isEmpty == false
    Row(
        Modifier.fillMaxWidth().background(DawColors.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton("편집") { close ->
            Item("실행 취소", vm.canUndo, close, vm::undo)
            Item("다시 실행", vm.canRedo, close, vm::redo)
            HorizontalDivider()
            Item("잘라내기", hasSel || hasRange, close, vm::cutSelection)
            Item("복사", hasSel || hasRange, close, vm::copySelection)
            Item("붙여넣기 (덮어쓰기)", hasClip, close, vm::paste)
            Item("끼워넣기 (뒤로 밀기)", hasClip, close, vm::pasteInsert)
            Item("삭제", hasSel || hasRange, close, vm::deleteSelection)
            Item("복제 (반복)", hasSel, close, vm::duplicateSelection)
            HorizontalDivider()
            Item("플레이헤드에서 자르기", true, close, vm::splitAtPlayhead)
            Item("트랙의 모든 리전 선택", true, close, vm::selectAllOnTrack)
            Item("선택 해제", hasSel || hasRange, close) { vm.clearSelection(); vm.selectRange(null) }
        }
        MenuButton("기능") { close ->
            Item("합치기 (Join)", vm.selectedRegions.size > 1, close, vm::joinSelection)
            Item("노멀라이즈", hasSel, close, vm::normalizeSelection)
            Item("리버스", hasSel, close, vm::reverseSelection)
            Item("리전 뮤트", hasSel, close, vm::toggleMuteSelection)
            Item("보컬 / 반주 분리 (AI)", vm.selectedRegions.size == 1, close, onSeparate)
            Item("크로스페이드 (20ms)", vm.selectedRegions.size > 1, close) { vm.crossfadeSelection(20) }
            Item("크로스페이드 (200ms)", vm.selectedRegions.size > 1, close) { vm.crossfadeSelection(200) }
            HorizontalDivider()
            Item("Flex: 속도 / 음정 / 템포…", true, close, onFlexTime)
            Item("Flex Pitch (음표 음정 편집)…", vm.selectedRegions.size == 1, close, onFlexPitch)
            Item("템포 감지 → 프로젝트 템포", true, close) { vm.detectTempo(apply = true) }
            Item("테이크 폴더 (컴핑)…", vm.selectedTrackId != null, close, onTakes)
            Item("선택 트랙으로 서밍 스택", true, close) { vm.createSummingStack(vm.selectedTrackIds()) }
            HorizontalDivider()
            Item("◀ 넛지 (그리드 1칸)", hasSel, close) { vm.nudge(-1) }
            Item("넛지 ▶ (그리드 1칸)", hasSel, close) { vm.nudge(1) }
            HorizontalDivider()
            Item("구간 삭제 + 당기기", hasRange, close, vm::rippleDeleteRange)
            Item("구간만 남기기", hasRange, close, vm::cropToRange)
            Item("구간 경계에서 자르기", hasRange, close, vm::splitAtRange)
            Item("무음 삽입", hasRange, close, vm::insertSilenceAtRange)
            Item("구간을 섹션으로 만들기", hasRange, close) { vm.addSection() }
        }
        MenuButton("탐색") { close ->
            Item("이전 마커로", true, close) { vm.gotoMarker(-1) }
            Item("다음 마커로", true, close) { vm.gotoMarker(1) }
            Item("플레이헤드에 마커 추가", true, close, vm::addMarker)
            Item("사이클 = 선택 범위", hasRange, close, vm::cycleFromRange)
        }
        MenuButton("믹스") { close ->
            Item((if (vm.automationMode) "✓ " else "   ") + "오토메이션 보기/편집 (A)", true, close) { vm.automationMode = !vm.automationMode }
            Item((if (vm.automationWrite) "✓ " else "   ") + "오토메이션 쓰기 (Latch: 재생 중 페이더 움직임 기록)", true, close) { vm.automationWrite = !vm.automationWrite }
            HorizontalDivider()
            Item("루프 · 미디어 브라우저…", true, close, onBrowser)
            Item("새 Aux (Space Designer 리버브)", true, close) { vm.addBus("space_designer") }
            Item("새 Aux (Stereo Delay)", true, close) { vm.addBus("stereo_delay") }
        }
        MenuButton("보기") { close ->
            Item((if (vm.showArrangement) "✓ " else "   ") + "아레인지먼트 트랙", true, close) { vm.showArrangement = !vm.showArrangement }
            Item((if (vm.showMarkers) "✓ " else "   ") + "마커 트랙", true, close) { vm.showMarkers = !vm.showMarkers }
            Item((if (vm.showInspector) "✓ " else "   ") + "인스펙터", true, close) { vm.showInspector = !vm.showInspector }
            HorizontalDivider()
            Item("화면에 곡 전체 맞추기", true, close) { timeline.fit(vm.project.contentEnd) }
            Item("선택 영역 확대", hasSel || hasRange, close) {
                val r = vm.range
                if (r != null) timeline.show(r.start, r.end) else {
                    val regions = vm.project.tracks.flatMap { it.regions }.filter { it.id in vm.selectedRegions }
                    if (regions.isNotEmpty()) timeline.show(regions.minOf { it.start }, regions.maxOf { it.end })
                }
            }
            Item("트랙 높이 크게", true, close) { vm.trackHeight = (vm.trackHeight + 16f).coerceAtMost(160f) }
            Item("트랙 높이 작게", true, close) { vm.trackHeight = (vm.trackHeight - 16f).coerceAtLeast(48f) }
        }
        Box(Modifier.width(1.dp).height(22.dp).background(DawColors.hairline))
        LogicButton(text = "A", active = vm.automationMode, activeColor = Color(0xFFFFD60A), width = 26.dp, height = 22.dp, contentDescription = "오토메이션") {
            vm.automationMode = !vm.automationMode
        }
        // left-click tool (Logic's tool menu)
        MenuButton(vm.tool.label, color = DawColors.accent) { close ->
            Tool.entries.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { vm.tool = t; close() }) }
        }
        MenuButton("스냅: ${vm.grid.label}", color = if (vm.grid == GridSize.OFF) DawColors.textDim else DawColors.cycle) { close ->
            GridSize.entries.forEach { g -> DropdownMenuItem(text = { Text(g.label) }, onClick = { vm.grid = g; close() }) }
        }
        Box(Modifier.width(8.dp))
        ZoomSlider(timeline, Modifier.width(120.dp).height(22.dp))
    }
}

@Composable
private fun Item(label: String, enabled: Boolean, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { close(); action() })
}

/** Logarithmic horizontal zoom slider. */
@Composable
private fun ZoomSlider(timeline: TimelineState, modifier: Modifier) {
    val tl by rememberUpdatedState(timeline)
    val lnMin = ln(TimelineState.MIN_ZOOM)
    val lnMax = ln(TimelineState.MAX_ZOOM)
    Canvas(
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { c, dx ->
                c.consume()
                val pos = (ln(tl.pxPerSecond) - lnMin) / (lnMax - lnMin) + dx / size.width
                val target = kotlin.math.exp(lnMin + pos.coerceIn(0f, 1f) * (lnMax - lnMin))
                tl.zoom(target / tl.pxPerSecond)
            }
        }
    ) {
        val cy = size.height / 2
        drawRoundRect(DawColors.knobTrack, Offset(0f, cy - 2f), Size(size.width, 4f), CornerRadius(2f, 2f))
        val f = ((ln(timeline.pxPerSecond) - lnMin) / (lnMax - lnMin)).coerceIn(0f, 1f)
        drawCircle(DawColors.faderCap, 7f * density, Offset(f * size.width, cy))
    }
}

/**
 * Touch-friendly quick action strip. Horizontal below the tracks in portrait, [vertical] on the
 * right edge in landscape so the tracks keep all the height.
 */
@Composable
fun QuickActions(
    vm: DawViewModel,
    onImport: () -> Unit,
    onSeparate: () -> Unit,
    onInspect: () -> Unit,
    vertical: Boolean = false,
    onFlexTime: () -> Unit = {},
    onBrowser: () -> Unit = {},
) {
    val hasRange = vm.range != null
    if (vertical) {
        Column(
            Modifier.fillMaxHeight().width(62.dp).background(DawColors.surface).verticalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (hasRange) {
                RangeActionButtons(vm)
                Box(Modifier.width(40.dp).height(1.dp).background(DawColors.hairline))
            }
            MainActionButtons(vm, onImport, onSeparate, onFlexTime, onBrowser)
        }
        return
    }
    Column(Modifier.fillMaxWidth().background(DawColors.surface)) {
        if (hasRange) {
            val r = vm.range!!
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(DawColors.laneSelected).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("구간 %.2fs · 트랙 %d".format(r.length.toFloat() / vm.project.sampleRate, r.trackIds.size), fontSize = 11.sp, color = DawColors.accent, modifier = Modifier.padding(end = 6.dp))
                RangeActionButtons(vm)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            MainActionButtons(vm, onImport, onSeparate, onFlexTime, onBrowser)
        }
    }
}

@Composable
private fun RangeActionButtons(vm: DawViewModel) {
    ToolButton(Icons.Filled.DeleteSweep, "삭제+당기기", onClick = vm::rippleDeleteRange)
    ToolButton(Icons.Filled.Crop, "구간만 남기기", onClick = vm::cropToRange)
    ToolButton(Icons.AutoMirrored.Filled.CallSplit, "경계 자르기", onClick = vm::splitAtRange)
    ToolButton(Icons.Filled.SpaceBar, "무음 삽입", onClick = vm::insertSilenceAtRange)
    ToolButton(Icons.Filled.ViewAgenda, "섹션 만들기") { vm.addSection() }
    ToolButton(Icons.Filled.Repeat, "사이클", onClick = vm::cycleFromRange)
    ToolButton(Icons.Filled.Close, "해제") { vm.selectRange(null); vm.selectSection(null) }
}

@Composable
private fun MainActionButtons(vm: DawViewModel, onImport: () -> Unit, onSeparate: () -> Unit, onFlexTime: () -> Unit, onBrowser: () -> Unit) {
    val hasSel = vm.selectedRegions.isNotEmpty()
    val hasRange = vm.range != null
    val hasClip = vm.clipboard?.isEmpty == false
    ToolButton(Icons.Filled.TouchApp, "포인터", selected = vm.tool == Tool.POINTER) { vm.tool = Tool.POINTER }
    ToolButton(Icons.Filled.SelectAll, "범위", selected = vm.tool == Tool.RANGE) { vm.tool = Tool.RANGE }
    ToolButton(Icons.Filled.ContentCut, "가위", selected = vm.tool == Tool.SCISSORS) { vm.tool = Tool.SCISSORS }
    ToolButton(Icons.Filled.LibraryMusic, "가져오기", onClick = onImport)
    ToolButton(Icons.Filled.FolderOpen, "루프", onClick = onBrowser)
    ToolButton(Icons.Filled.ShowChart, "오토메이션", selected = vm.automationMode) { vm.automationMode = !vm.automationMode }
    ToolButton(Icons.AutoMirrored.Filled.CallSplit, "자르기", onClick = vm::splitAtPlayhead)
    ToolButton(Icons.Filled.ContentCut, "잘라내기", enabled = hasSel || hasRange, onClick = vm::cutSelection)
    ToolButton(Icons.Filled.ContentCopy, "복사", enabled = hasSel || hasRange, onClick = vm::copySelection)
    ToolButton(Icons.Filled.ContentPaste, "붙여넣기", enabled = hasClip, onClick = vm::paste)
    ToolButton(Icons.Filled.VerticalAlignCenter, "끼워넣기", enabled = hasClip, onClick = vm::pasteInsert)
    ToolButton(Icons.Filled.Delete, "삭제", enabled = hasSel || hasRange, onClick = vm::deleteSelection)
    ToolButton(Icons.Filled.ControlPointDuplicate, "복제", enabled = hasSel, onClick = vm::duplicateSelection)
    ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "넛지 ◀", enabled = hasSel) { vm.nudge(-1) }
    ToolButton(Icons.AutoMirrored.Filled.ArrowForward, "넛지 ▶", enabled = hasSel) { vm.nudge(1) }
    ToolButton(Icons.Filled.Link, "합치기", enabled = vm.selectedRegions.size > 1, onClick = vm::joinSelection)
    ToolButton(Icons.Filled.Compare, "크로스페이드", enabled = vm.selectedRegions.size > 1) { vm.crossfadeSelection(20) }
    ToolButton(Icons.Filled.Speed, "Flex", onClick = onFlexTime)
    ToolButton(Icons.Filled.RecordVoiceOver, "보컬 분리", enabled = vm.selectedRegions.size == 1, onClick = onSeparate)
    ToolButton(Icons.Filled.GraphicEq, "노멀라이즈", enabled = hasSel, onClick = vm::normalizeSelection)
    ToolButton(Icons.Filled.SwapHoriz, "리버스", enabled = hasSel, onClick = vm::reverseSelection)
    ToolButton(Icons.AutoMirrored.Filled.VolumeOff, "리전 뮤트", enabled = hasSel, onClick = vm::toggleMuteSelection)
}

@Composable
fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> DawColors.textDim.copy(alpha = 0.35f)
        selected -> Color.White
        else -> DawColors.text
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) DawColors.accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .width(50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 9.sp, color = tint, maxLines = 1)
    }
}
