package com.musiccut.app.data

import android.content.Context
import com.musiccut.core.audio.Resampler
import com.musiccut.core.audio.WavCodec
import com.musiccut.core.audio.WavFormat
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.Project
import com.musiccut.core.project.SourcePool
import java.io.File

data class SavedProject(val name: String, val dir: File, val modified: Long)

/**
 * Stores projects in app-private storage: `projects/<name>/project.json` plus one 32-bit float WAV
 * per audio source (so edits stay lossless).
 */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<SavedProject> = root.listFiles()
        ?.filter { File(it, PROJECT_FILE).exists() }
        ?.map { SavedProject(it.name, it, File(it, PROJECT_FILE).lastModified()) }
        ?.sortedByDescending { it.modified }
        ?: emptyList()

    fun dirFor(name: String) = File(root, sanitize(name))

    fun save(project: Project, pool: SourcePool) {
        val dir = dirFor(project.name).apply { mkdirs() }
        for (src in project.sources) {
            val f = File(dir, src.fileName)
            if (f.exists()) continue
            val buf = pool[src.id] ?: continue
            val tmp = File(dir, src.fileName + ".tmp")
            tmp.outputStream().buffered().use { WavCodec.write(buf, it, WavFormat.FLOAT32) }
            tmp.renameTo(f)
        }
        // drop audio files no longer referenced
        val referenced = project.sources.map { it.fileName }.toSet()
        dir.listFiles { f -> f.name.endsWith(".wav") && f.name !in referenced }?.forEach { it.delete() }
        val tmp = File(dir, "$PROJECT_FILE.tmp")
        tmp.writeText(project.toJson())
        tmp.renameTo(File(dir, PROJECT_FILE))
    }

    fun load(name: String, pool: SourcePool, progress: (Float) -> Unit = {}): Project {
        val dir = dirFor(name)
        val project = Project.fromJson(File(dir, PROJECT_FILE).readText())
        PluginRegistry.ensureExternal(project)
        pool.clear()
        project.sources.forEachIndexed { i, src ->
            val f = File(dir, src.fileName)
            if (f.exists()) {
                val buf = f.inputStream().buffered().use { WavCodec.read(it) }
                pool.put(src.id, Resampler.resample(buf, project.sampleRate))
            }
            progress((i + 1f) / project.sources.size)
        }
        return project
    }

    fun delete(name: String) = dirFor(name).deleteRecursively()

    fun exists(name: String) = File(dirFor(name), PROJECT_FILE).exists()

    companion object {
        private const val PROJECT_FILE = "project.json"
        fun sanitize(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Untitled" }
    }
}
