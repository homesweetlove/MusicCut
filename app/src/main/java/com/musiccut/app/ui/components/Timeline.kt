package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.RangeSelection
import com.musiccut.app.ui.Tool
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.edit.EditOps
import com.musiccut.core.project.Project
import com.musiccut.core.project.Region
import com.musiccut.core.project.SourcePool
import com.musiccut.core.project.TimeUtil
import com.musiccut.core.project.Track
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val TRACK_HEADER_WIDTH = 156.dp
private val RULER_HEIGHT = 30.dp
private val LANE_HEIGHT = 22.dp

/** Horizontal scroll / zoom of a time-based view. */
@Stable
class TimelineState(val sampleRate: Int) {
    var scrollFrames by mutableFloatStateOf(0f)
    var pxPerSecond by mutableFloatStateOf(60f)
    var viewWidthPx by mutableFloatStateOf(1000f)

    val pxPerFrame: Float get() = pxPerSecond / sampleRate
    fun frameAt(x: Float): Long = (scrollFrames + x / pxPerFrame).toLong().coerceAtLeast(0)
    fun xOf(frame: Long): Float = (frame - scrollFrames) * pxPerFrame

    fun zoom(factor: Float, anchorX: Float = viewWidthPx / 2f) {
        val anchorFrame = scrollFrames + anchorX / pxPerFrame
        pxPerSecond = (pxPerSecond * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        scrollFrames = (anchorFrame - anchorX / pxPerFrame).coerceAtLeast(0f)
    }

    fun scrollBy(dxPx: Float) {
        scrollFrames = (scrollFrames - dxPx / pxPerFrame).coerceAtLeast(0f)
    }

    /** Zooms so [from, to) fills the view. */
    fun show(from: Long, to: Long, margin: Float = 0.05f) {
        if (to <= from) return
        pxPerSecond = (viewWidthPx * (1 - 2 * margin) / ((to - from).toFloat() / sampleRate)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        scrollFrames = (from - viewWidthPx * margin / pxPerFrame).coerceAtLeast(0f)
    }

    fun fit(frames: Long) {
        if (frames > 0) show(0, frames, 0.02f)
    }

    companion object {
        const val MIN_ZOOM = 2f
        const val MAX_ZOOM = 4000f
    }
}

private enum class Zone { NONE, BODY, TRIM_START, TRIM_END, FADE_IN, FADE_OUT }
private enum class DragMode { NONE, MOVE, TRIM_START, TRIM_END, FADE_IN, FADE_OUT, RANGE, SCROLL, AUTO_POINT }

private data class Hit(val trackIndex: Int, val region: Region?, val zone: Zone)

/** Logic's main window "Tracks" area: ruler, arrangement & marker tracks, track headers and lanes. */
@Composable
fun ArrangeView(
    vm: DawViewModel,
    timeline: TimelineState,
    onOpenRegion: (String) -> Unit,
    onOpenTrackPlugins: (String) -> Unit,
    modifier: Modifier = Modifier,
    showGlobalTracks: Boolean = true,
    onOpenTakes: (String) -> Unit = {},
) {
    val vScroll = rememberScrollState()
    val project = vm.project
    val density = LocalDensity.current
    val trackHeightDp = vm.trackHeight.dp

    AutoFollowPlayhead(vm, timeline)

    Column(modifier.fillMaxSize().background(DawColors.background)) {
        Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
            LaneHeader("${project.tracks.size} Tracks", onAdd = vm::addTrack)
            Ruler(vm, timeline, Modifier.weight(1f).fillMaxHeight())
        }
        if (showGlobalTracks && vm.showArrangement) {
            Row(Modifier.fillMaxWidth().height(LANE_HEIGHT)) {
                LaneHeader("Arrangement", onAdd = { vm.addSection() })
                ArrangementLane(vm, timeline, Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (showGlobalTracks && vm.showMarkers) {
            Row(Modifier.fillMaxWidth().height(LANE_HEIGHT)) {
                LaneHeader("Marker", onAdd = vm::addMarker)
                MarkerLane(vm, timeline, Modifier.weight(1f).fillMaxHeight())
            }
        }
        HorizontalDivider(color = DawColors.panelBorder, thickness = 1.dp)
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll)) {
            Column(Modifier.width(TRACK_HEADER_WIDTH)) {
                project.tracks.forEachIndexed { index, track ->
                    TrackHeader(
                        vm = vm,
                        track = track,
                        index = index,
                        height = trackHeightDp,
                        selected = track.id == vm.selectedTrackId,
                        onOpenPlugins = { onOpenTrackPlugins(track.id) },
                        onOpenTakes = { onOpenTakes(track.id) },
                    )
                }
                Box(Modifier.fillMaxWidth().height(64.dp).background(DawColors.surface))
            }
            Box(Modifier.weight(1f).height(trackHeightDp * project.tracks.size + 64.dp)) {
                TrackLanesCanvas(
                    vm = vm,
                    timeline = timeline,
                    tracks = project.tracks,
                    rowPx = with(density) { trackHeightDp.toPx() },
                    vScroll = vScroll,
                    onOpenRegion = onOpenRegion,
                    modifier = Modifier.fillMaxSize(),
                )
                PlayheadLayer(vm, timeline)
            }
        }
    }
}

@Composable
private fun LaneHeader(title: String, onAdd: () -> Unit) {
    Row(
        Modifier.width(TRACK_HEADER_WIDTH).fillMaxHeight().background(DawColors.surface).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 10.sp, color = DawColors.textDim, modifier = Modifier.weight(1f), maxLines = 1)
        Box(
            Modifier.size(22.dp).pointerInput(Unit) { detectTapGestures { onAdd() } },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, "추가", tint = DawColors.textDim, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun AutoFollowPlayhead(vm: DawViewModel, timeline: TimelineState) {
    // collected outside composition: no recomposition per position tick
    LaunchedEffect(vm, timeline) {
        vm.position.collect { pos ->
            if (!vm.playing.value) return@collect
            val x = timeline.xOf(pos)
            if (x > timeline.viewWidthPx * 0.92f || x < 0) {
                timeline.scrollFrames = (pos - (timeline.viewWidthPx * 0.08f) / timeline.pxPerFrame).coerceAtLeast(0f)
            }
        }
    }
}

/** Playhead in its own layer: position ticks only invalidate this draw call. */
@Composable
fun PlayheadLayer(vm: DawViewModel, timeline: TimelineState) {
    val position = vm.position.collectAsState()
    Canvas(Modifier.fillMaxSize()) {
        val x = timeline.xOf(position.value)
        if (x in 0f..size.width) {
            drawLine(Color.Black.copy(alpha = 0.5f), Offset(x + 1.5f, 0f), Offset(x + 1.5f, size.height), strokeWidth = 1f)
            drawLine(DawColors.playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
        }
    }
}

/** Bar ruler with Logic's yellow cycle strip: drag the top half to set the cycle, bottom half to scrub. */
@Composable
fun Ruler(vm: DawViewModel, timeline: TimelineState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val project = vm.project
    val position = vm.position.collectAsState()
    val vmState by rememberUpdatedState(vm)
    Canvas(
        modifier
            .background(DawColors.rulerBackground)
            .onSizeChanged { timeline.viewWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o ->
                        if (o.y < size.height * 0.42f) vmState.toggleCycle()
                        else vmState.seek(vmState.snap(timeline.frameAt(o.x)))
                    },
                )
            }
            .pointerInput(Unit) {
                var cycleDrag = false
                var startFrame = 0L
                detectDragGestures(
                    onDragStart = { o ->
                        cycleDrag = o.y < size.height * 0.42f
                        startFrame = vmState.snap(timeline.frameAt(o.x))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val f = vmState.snap(timeline.frameAt(change.position.x))
                        if (cycleDrag) {
                            if (f != startFrame) vmState.setCycleRange(startFrame, f)
                        } else vmState.seek(f)
                    },
                )
            }
    ) {
        val h = size.height
        val stripH = h * 0.42f
        drawRect(Color.Black.copy(alpha = 0.18f), Offset.Zero, Size(size.width, stripH))
        if (project.cycleEnd > project.cycleStart) {
            val x0 = timeline.xOf(project.cycleStart)
            val x1 = timeline.xOf(project.cycleEnd)
            val c = if (project.cycleEnabled) DawColors.cycle else DawColors.textDim.copy(alpha = 0.45f)
            drawRoundRect(c, Offset(x0, 2f), Size(x1 - x0, stripH - 4f), CornerRadius(3f, 3f))
        }
        drawBarGrid(project, timeline, h, ruler = true, measurer = measurer)
        drawLine(DawColors.panelBorder, Offset(0f, h - 0.5f), Offset(size.width, h - 0.5f))
        val x = timeline.xOf(position.value)
        if (x in -8f..size.width + 8f) {
            val p = Path().apply { moveTo(x - 6f, stripH); lineTo(x + 6f, stripH); lineTo(x, h); close() }
            drawPath(p, DawColors.playhead)
        }
    }
}

private fun DrawScope.drawBarGrid(project: Project, timeline: TimelineState, height: Float, ruler: Boolean, measurer: TextMeasurer? = null) {
    val fpBar = project.framesPerBar
    val fpBeat = project.framesPerBeat
    val barPx = fpBar * timeline.pxPerFrame
    val beatPx = fpBeat * timeline.pxPerFrame
    var barStep = 1
    while (barPx * barStep < 44) barStep *= 2
    val firstBar = floor(timeline.scrollFrames / fpBar).toInt().coerceAtLeast(0)
    var bar = firstBar - firstBar % barStep
    val top = if (ruler) height * 0.42f else 0f
    while (true) {
        val x = timeline.xOf((bar * fpBar).toLong())
        if (x > size.width) break
        if (x >= -2) {
            drawLine(DawColors.gridBar, Offset(x, top), Offset(x, height), strokeWidth = 1f)
            if (ruler && measurer != null) {
                drawText(measurer, "${bar + 1}", Offset(x + 3f, top), TextStyle(color = DawColors.text, fontSize = 10.sp))
            }
        }
        if (barStep == 1 && beatPx > 12) {
            for (b in 1 until project.beatsPerBar) {
                val bx = timeline.xOf((bar * fpBar + b * fpBeat).toLong())
                if (bx in 0f..size.width) drawLine(DawColors.gridBeat, Offset(bx, if (ruler) height * 0.8f else 0f), Offset(bx, height), strokeWidth = 1f)
            }
        }
        bar += barStep
    }
}

/** Arrangement track: song sections as coloured blocks (tap = select, long-press = menu). */
@Composable
private fun ArrangementLane(vm: DawViewModel, timeline: TimelineState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val vmState by rememberUpdatedState(vm)
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuX by remember { mutableFloatStateOf(0f) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    fun sectionAt(x: Float): String? {
        val f = timeline.frameAt(x)
        return vmState.project.sections.firstOrNull { f >= it.start && f < it.end }?.id
    }
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().background(DawColors.surface).pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> vmState.selectSection(sectionAt(o.x)) },
                    onLongPress = { o ->
                        sectionAt(o.x)?.let { menuFor = it; menuX = o.x; vmState.selectSection(it) }
                    },
                )
            }
        ) {
            for (s in vm.project.sections) {
                val x0 = timeline.xOf(s.start)
                val x1 = timeline.xOf(s.end)
                if (x1 < 0 || x0 > size.width) continue
                val c = DawColors.track(s.color)
                val selected = s.id == vm.selectedSectionId
                drawRoundRect(if (selected) DawColors.lighter(c, 0.25f) else DawColors.darker(c, 0.15f), Offset(x0 + 1f, 2f), Size(x1 - x0 - 2f, size.height - 4f), CornerRadius(4f, 4f))
                if (selected) drawRoundRect(Color.White, Offset(x0 + 1f, 2f), Size(x1 - x0 - 2f, size.height - 4f), CornerRadius(4f, 4f), style = Stroke(1.5f))
                val w = (x1 - x0 - 8f).toInt()
                if (w > 10) {
                    val layout = measurer.measure(s.name, TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = w))
                    drawText(layout, topLeft = Offset(max(x0, 0f) + 4f, (size.height - layout.size.height) / 2f))
                }
            }
        }
        val id = menuFor
        DropdownMenu(
            expanded = id != null,
            onDismissRequest = { menuFor = null },
            offset = DpOffset(with(density) { menuX.toDp() }, 0.dp),
        ) {
            if (id != null) {
                DropdownMenuItem(text = { Text("이름 변경") }, onClick = { renaming = id; menuFor = null })
                DropdownMenuItem(text = { Text("섹션 복제 (뒤에 반복)") }, onClick = { vm.duplicateSection(id); menuFor = null })
                DropdownMenuItem(text = { Text("◀ 앞 섹션과 자리 바꾸기") }, onClick = { vm.moveSection(id, -1); menuFor = null })
                DropdownMenuItem(text = { Text("뒤 섹션과 자리 바꾸기 ▶") }, onClick = { vm.moveSection(id, 1); menuFor = null })
                DropdownMenuItem(text = { Text("사이클로 반복 재생") }, onClick = { vm.cycleSection(id); menuFor = null })
                DropdownMenuItem(text = { Text("색상 바꾸기") }, onClick = {
                    val s = vm.project.sections.firstOrNull { it.id == id }
                    if (s != null) vm.setSectionColor(id, s.color + 1)
                    menuFor = null
                })
                DropdownMenuItem(text = { Text("표시만 지우기") }, onClick = { vm.removeSectionMarker(id); menuFor = null })
                DropdownMenuItem(text = { Text("섹션 삭제 (오디오 포함)", color = DawColors.record) }, onClick = { vm.deleteSection(id); menuFor = null })
            }
        }
    }
    renaming?.let { id ->
        val s = vm.project.sections.firstOrNull { it.id == id }
        TextInputDialog("섹션 이름", s?.name ?: "", onDismiss = { renaming = null }) { vm.renameSection(id, it); renaming = null }
    }
}

/** Marker track: tap a marker to jump there, long-press for rename / delete. */
@Composable
private fun MarkerLane(vm: DawViewModel, timeline: TimelineState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val vmState by rememberUpdatedState(vm)
    val density = LocalDensity.current
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuX by remember { mutableFloatStateOf(0f) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val touchPx = with(density) { 40.dp.toPx() }
    fun markerAt(x: Float): String? {
        val p = vmState.project
        return p.markers.lastOrNull { val mx = timeline.xOf(it.position); x >= mx - 6f && x <= mx + touchPx }?.id
    }
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().background(DawColors.surface).pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> markerAt(o.x)?.let { id -> vmState.project.markers.firstOrNull { it.id == id }?.let { vmState.seek(it.position) } } },
                    onLongPress = { o -> markerAt(o.x)?.let { menuFor = it; menuX = o.x } },
                )
            }
        ) {
            for (m in vm.project.markers) {
                val x = timeline.xOf(m.position)
                if (x < -200 || x > size.width) continue
                val c = DawColors.track(m.color)
                val layout = measurer.measure(m.name, TextStyle(color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                val w = layout.size.width + 10f
                drawRoundRect(c, Offset(x, 2f), Size(w, size.height - 4f), CornerRadius(3f, 3f))
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                drawText(layout, topLeft = Offset(x + 5f, (size.height - layout.size.height) / 2f))
            }
        }
        val id = menuFor
        DropdownMenu(expanded = id != null, onDismissRequest = { menuFor = null }, offset = DpOffset(with(density) { menuX.toDp() }, 0.dp)) {
            if (id != null) {
                DropdownMenuItem(text = { Text("이름 변경") }, onClick = { renaming = id; menuFor = null })
                DropdownMenuItem(text = { Text("마커 삭제", color = DawColors.record) }, onClick = { vm.removeMarker(id); menuFor = null })
            }
        }
    }
    renaming?.let { id ->
        val m = vm.project.markers.firstOrNull { it.id == id }
        TextInputDialog("마커 이름", m?.name ?: "", onDismiss = { renaming = null }) { vm.renameMarker(id, it); renaming = null }
    }
}

/**
 * Track lanes with regions. Used by the main window and the audio track editor ([tracks] is the
 * subset shown, one row each). Handles select / move / trim / fade / scissors / marquee and
 * two-finger pinch zoom.
 */
@Composable
fun TrackLanesCanvas(
    vm: DawViewModel,
    timeline: TimelineState,
    tracks: List<Track>,
    rowPx: Float,
    vScroll: ScrollState?,
    onOpenRegion: (String) -> Unit,
    modifier: Modifier,
    detailed: Boolean = false,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { 18.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val vmRef by rememberUpdatedState(vm)
    val tracksRef by rememberUpdatedState(tracks)
    val rowRef by rememberUpdatedState(rowPx)
    val openRegion by rememberUpdatedState(onOpenRegion)

    fun hitTest(o: Offset): Hit {
        val row = rowRef
        val ti = floor(o.y / row).toInt()
        val track = tracksRef.getOrNull(ti) ?: return Hit(ti, null, Zone.NONE)
        val localY = o.y - ti * row
        var best: Region? = null
        var zone = Zone.NONE
        for (r in track.regions) {
            val x0 = timeline.xOf(r.start)
            val x1 = timeline.xOf(r.end)
            val edge = min(edgePx, (x1 - x0) / 4f)
            if (o.x < x0 - edge / 2 || o.x > x1 + edge / 2) continue
            best = r
            val top = localY < row * 0.35f
            zone = when {
                o.x - x0 < edge -> if (top) Zone.FADE_IN else Zone.TRIM_START
                x1 - o.x < edge -> if (top) Zone.FADE_OUT else Zone.TRIM_END
                else -> Zone.BODY
            }
            if (zone == Zone.BODY) break
        }
        return Hit(ti, best, zone)
    }

    // ---- automation helpers (normalized value ↔ y inside a row)
    val autoPad = with(density) { 8.dp.toPx() }
    fun autoY(row: Int, v: Float): Float = row * rowRef + autoPad + (1f - v) * (rowRef - 2 * autoPad)
    fun autoValue(row: Int, y: Float): Float = (1f - (y - row * rowRef - autoPad) / (rowRef - 2 * autoPad)).coerceIn(0f, 1f)
    fun autoPointAt(o: Offset): Pair<Int, Int>? {
        val row = floor(o.y / rowRef).toInt()
        val t = tracksRef.getOrNull(row) ?: return null
        val target = vmRef.automationTarget(t.id)
        val lane = t.automation.firstOrNull { it.target == target } ?: return null
        val idx = lane.points.indices.minByOrNull { i ->
            val p = lane.points[i]
            kotlin.math.hypot(timeline.xOf(p.frame) - o.x, autoY(row, p.value) - o.y)
        } ?: return null
        val p = lane.points[idx]
        return if (kotlin.math.hypot(timeline.xOf(p.frame) - o.x, autoY(row, p.value) - o.y) < edgePx * 1.4f) row to idx else null
    }

    Canvas(
        modifier
            .onSizeChanged { if (!detailed) timeline.viewWidthPx = it.width.toFloat() }
            // two-finger pinch zoom; consumed in the Initial pass so single-finger gestures cancel
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) timeline.zoom(zoom, event.calculateCentroid().x)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o ->
                        val v = vmRef
                        if (v.automationMode) {
                            val row = floor(o.y / rowRef).toInt()
                            tracksRef.getOrNull(row)?.let { t ->
                                v.selectTrack(t.id)
                                if (autoPointAt(o) == null) v.addAutomationPoint(t.id, v.automationTarget(t.id), v.snap(timeline.frameAt(o.x)), autoValue(row, o.y))
                            }
                            return@detectTapGestures
                        }
                        val hit = hitTest(o)
                        val frame = v.snap(timeline.frameAt(o.x))
                        tracksRef.getOrNull(hit.trackIndex)?.let { v.selectTrack(it.id) }
                        when {
                            v.tool == Tool.SCISSORS && hit.region != null -> v.splitRegionAt(hit.region.id, frame)
                            v.tool == Tool.POINTER && hit.region != null -> v.selectRegion(hit.region.id)
                            else -> {
                                v.clearSelection()
                                v.selectRange(null)
                                v.selectSection(null)
                                v.seek(frame)
                            }
                        }
                    },
                    onDoubleTap = { o ->
                        val hit = hitTest(o)
                        if (hit.region != null) {
                            vmRef.selectRegion(hit.region.id)
                            openRegion(hit.region.id)
                        }
                    },
                    onLongPress = { o ->
                        val v = vmRef
                        if (v.automationMode) {
                            autoPointAt(o)?.let { (row, idx) ->
                                val t = tracksRef[row]
                                v.removeAutomationPoint(t.id, v.automationTarget(t.id), idx)
                            }
                        } else hitTest(o).region?.let { v.selectRegion(it.id, additive = true) }
                    },
                )
            }
            .pointerInput(Unit) {
                var mode = DragMode.NONE
                var acc = Offset.Zero
                var origin = Offset.Zero
                var target: Region? = null
                var ids = emptySet<String>()
                var autoRow = -1
                var autoIdx = -1
                var autoBase: com.musiccut.core.project.Project? = null
                detectDragGestures(
                    onDragStart = { o ->
                        val v = vmRef
                        acc = Offset.Zero
                        origin = o
                        if (v.automationMode) {
                            val ap = autoPointAt(o)
                            if (ap != null) {
                                autoRow = ap.first; autoIdx = ap.second
                                v.beginGesture("오토메이션 이동")
                                autoBase = v.project
                                mode = DragMode.AUTO_POINT
                            } else mode = DragMode.SCROLL
                            return@detectDragGestures
                        }
                        val hit = hitTest(o)
                        target = hit.region
                        mode = when {
                            v.tool == Tool.RANGE -> DragMode.RANGE
                            v.tool == Tool.POINTER && hit.region != null -> when (hit.zone) {
                                Zone.TRIM_START -> DragMode.TRIM_START
                                Zone.TRIM_END -> DragMode.TRIM_END
                                Zone.FADE_IN -> DragMode.FADE_IN
                                Zone.FADE_OUT -> DragMode.FADE_OUT
                                else -> DragMode.MOVE
                            }
                            else -> DragMode.SCROLL
                        }
                        val r = hit.region
                        when (mode) {
                            DragMode.MOVE -> {
                                ids = if (r != null && r.id in v.selectedRegions) v.selectedRegions else setOfNotNull(r?.id)
                                if (r != null && r.id !in v.selectedRegions) v.selectRegion(r.id)
                                v.beginGesture("이동")
                            }
                            DragMode.TRIM_START, DragMode.TRIM_END -> v.beginGesture("길이 조절")
                            DragMode.FADE_IN, DragMode.FADE_OUT -> v.beginGesture("페이드")
                            else -> Unit
                        }
                    },
                    onDragEnd = { vmRef.endGesture(); mode = DragMode.NONE },
                    onDragCancel = { vmRef.endGesture(); mode = DragMode.NONE },
                    onDrag = { change, amount ->
                        change.consume()
                        acc += amount
                        val v = vmRef
                        val r = target
                        val dFrames = (acc.x / timeline.pxPerFrame).toLong()
                        when (mode) {
                            DragMode.SCROLL -> {
                                timeline.scrollBy(amount.x)
                                vScroll?.dispatchRawDelta(-amount.y)
                            }
                            DragMode.MOVE -> if (r != null) {
                                val newStart = v.snap((r.start + dFrames).coerceAtLeast(0))
                                val trackDelta = if (tracksRef.size > 1) (acc.y / rowRef).roundToInt() else 0
                                v.updateGesture { EditOps.moveMany(it, ids, newStart - r.start, trackDelta) }
                            }
                            DragMode.TRIM_START -> if (r != null) v.updateGesture { EditOps.trimStart(it, r.id, v.snap(r.start + dFrames)) }
                            DragMode.TRIM_END -> if (r != null) v.updateGesture { EditOps.trimEnd(it, r.id, v.snap(r.end + dFrames)) }
                            DragMode.FADE_IN -> if (r != null) v.updateGesture { EditOps.setFades(it, r.id, max(0L, r.fadeIn + dFrames), null) }
                            DragMode.FADE_OUT -> if (r != null) v.updateGesture { EditOps.setFades(it, r.id, null, max(0L, r.fadeOut - dFrames)) }
                            DragMode.RANGE -> {
                                val list = tracksRef
                                val a = v.snap(timeline.frameAt(origin.x))
                                val b = v.snap(timeline.frameAt(origin.x + acc.x))
                                val last = max(0, list.size - 1)
                                val t0 = floor(origin.y / rowRef).toInt().coerceIn(0, last)
                                val t1 = floor((origin.y + acc.y) / rowRef).toInt().coerceIn(0, last)
                                val trackIds = list.subList(min(t0, t1), min(list.size, max(t0, t1) + 1)).map { it.id }.toSet()
                                v.clearSelection()
                                v.selectRange(RangeSelection(min(a, b), max(a, b), trackIds))
                            }
                            DragMode.AUTO_POINT -> {
                                val base = autoBase
                                val t = tracksRef.getOrNull(autoRow)
                                if (base != null && t != null) {
                                    val pos = origin + acc
                                    val frame = v.snap(timeline.frameAt(pos.x))
                                    val value = autoValue(autoRow, pos.y)
                                    val tgt = v.automationTarget(t.id)
                                    v.updateGesture { v.moveAutomationPoint(base, t.id, tgt, autoIdx, frame, value) }
                                }
                            }
                            DragMode.NONE -> Unit
                        }
                    },
                )
            }
    ) {
        val w = size.width
        val selectedTrack = vm.selectedTrackId
        tracks.forEachIndexed { i, t ->
            drawRect(if (t.id == selectedTrack && !detailed) DawColors.laneSelected else DawColors.laneEven, Offset(0f, i * rowPx), Size(w, rowPx))
            drawLine(DawColors.laneSeparator, Offset(0f, (i + 1) * rowPx - 0.5f), Offset(w, (i + 1) * rowPx - 0.5f), strokeWidth = 1f)
        }
        drawBarGrid(vm.project, timeline, size.height, ruler = false)

        val p = vm.project
        if (p.cycleEnabled && p.cycleEnd > p.cycleStart) {
            val x0 = timeline.xOf(p.cycleStart)
            val x1 = timeline.xOf(p.cycleEnd)
            drawRect(DawColors.cycle.copy(alpha = 0.05f), Offset(x0, 0f), Size(x1 - x0, size.height))
        }

        val selected = vm.selectedRegions
        tracks.forEachIndexed { i, t ->
            for (r in t.regions) {
                val x0 = timeline.xOf(r.start)
                val x1 = timeline.xOf(r.end)
                if (x1 < 0 || x0 > w) continue
                drawRegion(r, vm.pool, timeline, x0, x1, i * rowPx, rowPx, r.id in selected, measurer, detailed)
            }
        }

        vm.range?.let { rs ->
            val x0 = timeline.xOf(rs.start)
            val x1 = timeline.xOf(rs.end)
            tracks.forEachIndexed { i, t ->
                if (t.id in rs.trackIds) {
                    drawRect(Color.White.copy(alpha = 0.16f), Offset(x0, i * rowPx), Size(x1 - x0, rowPx))
                    drawRect(Color.White.copy(alpha = 0.8f), Offset(x0, i * rowPx), Size(x1 - x0, rowPx), style = Stroke(1f))
                }
            }
        }

        if (vm.automationMode) {
            tracks.forEachIndexed { row, t ->
                val top = row * rowPx
                drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top), Size(w, rowPx))
                val target = vm.automationTarget(t.id)
                val lane = t.automation.firstOrNull { it.target == target }
                val lineColor = Color(0xFFFFD60A)
                if (lane == null || lane.points.isEmpty()) {
                    val y = autoY(row, com.musiccut.core.engine.Automation.currentValue(t, target))
                    var x = 0f
                    while (x < w) { drawLine(lineColor.copy(alpha = 0.5f), Offset(x, y), Offset(x + 8f, y), strokeWidth = 2f); x += 16f }
                } else {
                    val pts = lane.points
                    val path = Path()
                    path.moveTo(0f, autoY(row, pts.first().value))
                    pts.forEach { pt -> path.lineTo(timeline.xOf(pt.frame), autoY(row, pt.value)) }
                    path.lineTo(w, autoY(row, pts.last().value))
                    drawPath(path, lineColor, style = Stroke(2.5f))
                    pts.forEach { pt ->
                        val x = timeline.xOf(pt.frame)
                        if (x in -10f..w + 10f) {
                            drawCircle(lineColor, 6.dp.toPx(), Offset(x, autoY(row, pt.value)))
                            drawCircle(Color.Black, 6.dp.toPx(), Offset(x, autoY(row, pt.value)), style = Stroke(1.5f))
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRegion(
    r: Region,
    pool: SourcePool,
    timeline: TimelineState,
    x0: Float,
    x1: Float,
    top: Float,
    rowPx: Float,
    selected: Boolean,
    measurer: TextMeasurer,
    detailed: Boolean,
) {
    val base = DawColors.track(r.color)
    val pad = 1.5f
    val y0 = top + pad
    val h = rowPx - pad * 2
    val headerH = min(15f * density, h * 0.24f)
    val left = x0
    val width = max(2f, x1 - x0)
    val radius = CornerRadius(4f, 4f)
    val body = when {
        r.muted -> Color(0xFF5A5A5E)
        selected -> DawColors.lighter(base, 0.28f)
        else -> base
    }
    val bodyAlpha = if (r.muted) 0.55f else 1f
    drawRoundRect(body.copy(alpha = 0.78f * bodyAlpha), Offset(left, y0), Size(width, h), radius)
    drawRoundRect(DawColors.darker(body, 0.18f).copy(alpha = bodyAlpha), Offset(left, y0), Size(width, headerH), radius)

    clipRect(max(0f, left), y0, min(size.width, left + width), y0 + h) {
        val textWidth = (width - 8f).toInt()
        if (textWidth > 12) {
            val label = buildString {
                append(r.name)
                if (r.reversed) append("  ⇆")
                if (r.gainDb != 0f) append("  %+.1f dB".format(r.gainDb))
                if (r.muted) append("  (mute)")
            }
            val layout = measurer.measure(
                label,
                TextStyle(color = if (selected) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = textWidth),
            )
            drawText(layout, topLeft = Offset(max(left, 0f) + 4f, y0 + (headerH - layout.size.height) / 2f))
        }
        val peaks = pool.peaks(r.sourceId)
        if (peaks != null) {
            val waveTop = y0 + headerH + 1f
            val waveH = h - headerH - 2f
            val cy = waveTop + waveH / 2f
            val amp = waveH / 2f * dbToGain(r.gainDb).coerceAtMost(4f)
            val framesPerPx = 1f / timeline.pxPerFrame
            val waveColor = if (selected) DawColors.darker(base, 0.55f) else DawColors.darker(base, 0.62f)
            if (detailed) {
                drawLine(waveColor.copy(alpha = 0.35f), Offset(max(0f, x0), cy), Offset(min(size.width, x1), cy), strokeWidth = 1f)
            }
            val startX = max(0f, x0).toInt()
            val endX = min(size.width, x1).toInt()
            val dec = peaks.samplesPerPeak
            for (x in startX until endX) {
                val rel = (timeline.scrollFrames + x * framesPerPx) - r.start
                if (rel < 0 || rel >= r.length) continue
                val s0: Float
                val s1: Float
                if (!r.reversed) {
                    s0 = r.sourceOffset + rel
                    s1 = s0 + framesPerPx
                } else {
                    s1 = r.sourceOffset + r.length - rel
                    s0 = s1 - framesPerPx
                }
                val p0 = (s0 / dec).toInt().coerceIn(0, peaks.size - 1)
                val p1 = max(p0 + 1, (s1 / dec).toInt()).coerceAtMost(peaks.size)
                var lo = 0f
                var hi = 0f
                for (k in p0 until p1) {
                    if (peaks.min[k] < lo) lo = peaks.min[k]
                    if (peaks.max[k] > hi) hi = peaks.max[k]
                }
                var env = 1f
                if (r.fadeIn > 0 && rel < r.fadeIn) env *= rel / r.fadeIn
                val remain = r.length - rel
                if (r.fadeOut > 0 && remain < r.fadeOut) env *= remain / r.fadeOut
                val yHi = (cy - hi * amp * env).coerceIn(waveTop, waveTop + waveH)
                val yLo = (cy - lo * amp * env).coerceIn(waveTop, waveTop + waveH)
                drawLine(waveColor, Offset(x.toFloat(), yHi), Offset(x.toFloat(), max(yLo, yHi + 1f)), strokeWidth = 1f)
            }
        }
        // fade curves (Logic draws them as dark shaded triangles)
        val fadeShade = Color.Black.copy(alpha = 0.28f)
        if (r.fadeIn > 0) {
            val fx = x0 + r.fadeIn * timeline.pxPerFrame
            val path = Path().apply { moveTo(x0, y0 + headerH); lineTo(fx, y0 + headerH); lineTo(x0, y0 + h); close() }
            drawPath(path, fadeShade)
            drawLine(Color.White.copy(alpha = 0.85f), Offset(x0, y0 + h), Offset(fx, y0 + headerH), strokeWidth = 1.5f)
        }
        if (r.fadeOut > 0) {
            val fx = x1 - r.fadeOut * timeline.pxPerFrame
            val path = Path().apply { moveTo(fx, y0 + headerH); lineTo(x1, y0 + headerH); lineTo(x1, y0 + h); close() }
            drawPath(path, fadeShade)
            drawLine(Color.White.copy(alpha = 0.85f), Offset(fx, y0 + headerH), Offset(x1, y0 + h), strokeWidth = 1.5f)
        }
    }
    if (selected) {
        drawRoundRect(Color.White, Offset(left, y0), Size(width, h), radius, style = Stroke(width = 1.5f))
        // fade handles (top corners) and trim handles (bottom corners)
        val hs = 7f * density
        drawRect(Color.White, Offset(left + 1f, y0 + headerH), Size(hs * 0.6f, hs * 0.6f))
        drawRect(Color.White, Offset(left + width - 1f - hs * 0.6f, y0 + headerH), Size(hs * 0.6f, hs * 0.6f))
        drawLine(Color.White, Offset(left + 2f, y0 + h - hs * 1.6f), Offset(left + 2f, y0 + h - 3f), strokeWidth = 3f)
        drawLine(Color.White, Offset(left + width - 2f, y0 + h - hs * 1.6f), Offset(left + width - 2f, y0 + h - 3f), strokeWidth = 3f)
    } else {
        drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(left, y0), Size(width, h), radius, style = Stroke(width = 1f))
    }
}

/** Formats the LCD display. */
fun lcdText(project: Project, frame: Long): Pair<String, String> =
    TimeUtil.barsBeats(frame, project) to TimeUtil.clock(frame, project.sampleRate)
