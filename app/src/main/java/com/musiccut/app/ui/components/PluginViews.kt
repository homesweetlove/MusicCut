package com.musiccut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musiccut.app.ui.DawViewModel
import com.musiccut.app.ui.MASTER_ID
import com.musiccut.app.ui.theme.DawColors
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginAnalysis
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.PluginSlot

/** Plugin picker grouped by category, like Logic's insert slot menu. */
@Composable
fun PluginBrowserDialog(onDismiss: () -> Unit, onPick: (PluginDescriptor) -> Unit) {
    val groups = remember { PluginRegistry.byCategory() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("플러그인 추가") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                groups.forEach { (cat, list) ->
                    item(key = cat.name) {
                        Text(cat.label, color = DawColors.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    }
                    items(list, key = { it.id }) { d ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onPick(d) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(d.name, fontSize = 15.sp)
                            Text(d.description, fontSize = 11.sp, color = DawColors.textDim)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** Lists the insert chain of a track / master with reorder & add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginChainSheet(vm: DawViewModel, ownerId: String, onDismiss: () -> Unit, onEdit: (String) -> Unit, onAdd: () -> Unit) {
    val slots = vm.pluginsOf(ownerId)
    val title = if (ownerId == MASTER_ID) "Stereo Out" else vm.project.track(ownerId)?.name ?: ""
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("$title · Audio FX", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (slots.isEmpty()) Text("아직 플러그인이 없습니다", color = DawColors.textDim)
            slots.forEachIndexed { i, slot ->
                val d = PluginRegistry.find(slot.pluginId)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onEdit(slot.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}. ${d?.name ?: slot.pluginId}", modifier = Modifier.weight(1f), color = if (slot.bypassed) DawColors.textDim else DawColors.text)
                    Switch(checked = !slot.bypassed, onCheckedChange = { vm.setPluginBypass(ownerId, slot.id, !it) })
                    IconButton(onClick = { vm.movePlugin(ownerId, slot.id, -1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "위로") }
                    IconButton(onClick = { vm.movePlugin(ownerId, slot.id, 1) }, enabled = i < slots.size - 1) { Icon(Icons.Filled.ArrowDownward, "아래로") }
                    IconButton(onClick = { vm.removePlugin(ownerId, slot.id) }) { Icon(Icons.Filled.Delete, "삭제") }
                }
            }
            Spacer(Modifier.height(8.dp))
            AssistChip(onClick = onAdd, label = { Text("+ 플러그인 추가") })
        }
    }
}

