package com.musiccut.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.theme.DawColors

/**
 * Logic's Audio Track Editor: the selected track in a tall lane with its own zoom, for precise
 * trimming, fades and scissor cuts. Zooms to the selected region automatically.
 */
@Composable
fun AudioTrackEditor(vm: DawViewModel, onOpenRegion: (String) -> Unit, modifier: Modifier = Modifier) {
    val timeline = remember { TimelineState(vm.sampleRate) }
    val regionId = vm.selectedRegions.firstOrNull()
    val project = vm.project
    val track = regionId?.let { project.findRegion(it)?.first } ?: project.track(vm.selectedTrackId ?: "")
    val region = regionId?.let { project.findRegion(it)?.second }

    LaunchedEffect(regionId, timeline.viewWidthPx) {
        if (region != null) timeline.show(region.start, region.end, 0.06f)
    }
    AutoFollowPlayhead(vm, timeline)

    Column(modifier.fillMaxWidth().background(DawColors.panel)) {
        Row(
            Modifier.fillMaxWidth().background(DawColors.surfaceHigh).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Audio Track Editor", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(track?.name ?: "트랙 없음", fontSize = 11.sp, color = DawColors.textDim)
            if (region != null) {
                Box(Modifier.padding(start = 8.dp)) {
                    LogicButton(text = "맞춤", width = 44.dp, height = 22.dp) { timeline.show(region.start, region.end, 0.06f) }
                }
            }
        }
        Ruler(vm, timeline, Modifier.fillMaxWidth().height(26.dp))
        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("트랙이나 리전을 선택하세요", color = DawColors.textDim, fontSize = 12.sp)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val rowPx = with(LocalDensity.current) { maxHeight.toPx() }
                TrackLanesCanvas(
                    vm = vm,
                    timeline = timeline,
                    tracks = listOf(track),
                    rowPx = rowPx,
                    vScroll = null,
                    onOpenRegion = onOpenRegion,
                    modifier = Modifier.fillMaxSize(),
                    detailed = true,
                )
                PlayheadLayer(vm, timeline)
            }
        }
    }
}
