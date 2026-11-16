package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.edit.EditOps
import com.musiccut.core.project.TimeUtil

/** Logic's left-hand Inspector: region parameters, track parameters and two channel strips. */
@Composable
fun Inspector(
    vm: DawViewModel,
    onEditPlugin: (String, String) -> Unit,
    onAddPlugin: (String) -> Unit,
    onSeparate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = vm.project
    val sr = project.sampleRate
    val regionId = vm.selectedRegions.singleOrNull()
    val found = regionId?.let { project.findRegion(it) }
    var renaming by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(214.dp)
            .fillMaxHeight()
            .background(DawColors.panel)
            .verticalScroll(rememberScrollState()),
    ) {
        if (found != null) {
            val r = found.second
            Disclosure("Region: ${r.name}") {
                InspectorRow("이름", r.name) { renaming = true }
                InspectorRow("위치", TimeUtil.barsBeats(r.start, project))
                InspectorRow("길이", "%.2f s".format(r.length.toFloat() / sr))
                InspectorRow("뮤트", if (r.muted) "On" else "Off", onClick = vm::toggleMuteSelection)
                InspectorRow("리버스", if (r.reversed) "On" else "Off", onClick = vm::reverseSelection)
                InspectorSlider("게인", r.gainDb, -24f, 12f, "%+.1f dB".format(r.gainDb), vm, "리전 게인") { p, v -> EditOps.setGain(p, setOf(r.id), v) }
                val maxFade = minOf(r.length, (sr * 10).toLong()).toFloat()
                InspectorSlider("페이드 인", r.fadeIn.toFloat(), 0f, maxFade, "%.2f s".format(r.fadeIn.toFloat() / sr), vm, "페이드 인") { p, v -> EditOps.setFades(p, r.id, v.toLong(), null) }
                InspectorSlider("페이드 아웃", r.fadeOut.toFloat(), 0f, maxFade, "%.2f s".format(r.fadeOut.toFloat() / sr), vm, "페이드 아웃") { p, v -> EditOps.setFades(p, r.id, null, v.toLong()) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    LogicButton(text = "노멀", width = 46.dp, onClick = vm::normalizeSelection)
                    LogicButton(text = "자르기", width = 50.dp, onClick = vm::splitAtPlayhead)
                    LogicButton(text = "보컬분리", width = 64.dp, onClick = onSeparate)
                }
            }
        } else {
            Disclosure("Region") {
                Text(
                    if (vm.selectedRegions.size > 1) "리전 ${vm.selectedRegions.size}개 선택됨" else "리전을 선택하면 게인·페이드 등을 편집할 수 있습니다",
                    fontSize = 11.sp, color = DawColors.textDim,
                )
            }
        }
        val track = project.track(vm.selectedTrackId ?: "")
        if (track != null) {
            Disclosure("Track: ${track.name}", initiallyOpen = false) {
                InspectorRow("볼륨", "%.1f dB".format(track.volumeDb))
                InspectorRow("팬", when { track.pan < -0.01f -> "L${(-track.pan * 64).toInt()}"; track.pan > 0.01f -> "R${(track.pan * 64).toInt()}"; else -> "C" })
                InspectorRow("리전 수", "${track.regions.size}")
                InspectorRow("플러그인", "${track.plugins.size}")
                InspectorRow("센드", "${track.sends.size}")
            }
        }
        Disclosure("Channel Strips") {
            InspectorStrips(vm, onEditPlugin, onAddPlugin)
        }
    }
    if (renaming && found != null) {
        TextInputDialog("리전 이름", found.second.name, onDismiss = { renaming = false }) { vm.renameRegion(found.second.id, it); renaming = false }
    }
}

/**
 * Horizontal drag slider for inspector values. The whole drag is one undo step (begin / update /
 * end gesture on the view model).
 */
@Composable
fun InspectorSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    valueText: String,
    vm: DawViewModel,
    undoLabel: String,
    apply: (com.musiccut.core.project.Project, Float) -> com.musiccut.core.project.Project,
) {
    val current by rememberUpdatedState(value)
    val applyRef by rememberUpdatedState(apply)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, color = DawColors.textDim, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 11.sp, color = DawColors.text)
        }
        Canvas(
            Modifier.fillMaxWidth().height(16.dp).pointerInput(Unit) {
                var v = 0f
                detectHorizontalDragGestures(
                    onDragStart = { v = current; vm.beginGesture(undoLabel) },
                    onDragEnd = { vm.endGesture() },
                    onDragCancel = { vm.endGesture() },
                ) { c, dx ->
                    c.consume()
                    v = (v + dx / size.width * (max - min)).coerceIn(min, max)
                    val target = v
                    vm.updateGesture { applyRef(it, target) }
                }
            }
        ) {
            val cy = size.height / 2
            val f = if (max > min) ((value - min) / (max - min)).coerceIn(0f, 1f) else 0f
            drawRoundRect(DawColors.knobTrack, Offset(0f, cy - 2f), Size(size.width, 4f), CornerRadius(2f, 2f))
            drawRoundRect(DawColors.accent, Offset(0f, cy - 2f), Size(size.width * f, 4f), CornerRadius(2f, 2f))
            drawCircle(DawColors.faderCap, 6f * density, Offset(size.width * f, cy))
        }
    }
}
