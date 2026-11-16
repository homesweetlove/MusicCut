package com.musiccut.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.musiccut.app.data.LogicProjectImporter
import com.musiccut.core.plugin.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Imports a Logic project without exposing DawViewModel's internal state setters. */
fun DawViewModel.importLogicProject(uri: Uri) {
    stop()
    toast("Logic 프로젝트 분석 중…")
    viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                LogicProjectImporter.import(getApplication<Application>(), uri, sampleRate)
            }
        }.onSuccess { result ->
            pool.clear()
            result.audio.forEach { (id, audio) -> pool.put(id, audio) }
            PluginRegistry.ensureExternal(result.project)
            edit("Logic 프로젝트 가져오기") { result.project }
            seek(0)
            val preserved = if (result.preservedPlugins > 0) " · AU 보존 ${result.preservedPlugins}" else ""
            val warning = if (result.warnings.isNotEmpty()) " · 변환 주의 ${result.warnings.size}" else ""
            toast("Logic 가져오기 완료 · 변환 플러그인 ${result.mappedPlugins}$preserved$warning")
        }.onFailure { error ->
            toast("Logic 프로젝트 가져오기 실패: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
