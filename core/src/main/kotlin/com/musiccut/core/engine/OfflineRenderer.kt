package com.musiccut.core.engine

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.edit.EditOps
import com.musiccut.core.project.AudioSourceInfo
import com.musiccut.core.project.Project
import com.musiccut.core.project.Region
import com.musiccut.core.project.SourcePool
import com.musiccut.core.project.newId

object OfflineRenderer {

    /** Bounces the full mix (all tracks, plugins, master) between [from] and [to]. */
    fun bounce(
        project: Project,
        pool: SourcePool,
        from: Long,
        to: Long,
        tailSeconds: Float = 0f,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AudioBuffer {
        val total = (to - from + (tailSeconds * project.sampleRate).toLong()).coerceAtLeast(0)
        require(total < Int.MAX_VALUE) { "Range too long" }
        val out = AudioBuffer.silent(total.toInt(), project.sampleRate)
        val mixer = Mixer(pool, project.sampleRate)
        val block = 8192
        var pos = 0
        // Tail: regions past `to` must not play, so render from a trimmed copy.
        val trimmed = EditOps.clearRange(project, project.tracks.map { it.id }.toSet(), to, Long.MAX_VALUE / 4)
        val bl = FloatArray(block)
        val br = FloatArray(block)
        while (pos < total) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val n = minOf(block, (total - pos).toInt())
            mixer.render(trimmed, from + pos, n, bl, br)
            bl.copyInto(out.left, pos, 0, n)
            br.copyInto(out.right, pos, 0, n)
            pos += n
            progress(pos.toFloat() / total)
        }
        return out
    }

    /** Renders the raw (pre-fader, no plugins) audio of the given regions on one track. */
    fun renderRegionsDry(pool: SourcePool, regions: List<Region>, sampleRate: Int): Pair<Long, AudioBuffer> {
        val start = regions.minOf { it.start }
        val end = regions.maxOf { it.end }
        val out = AudioBuffer.silent((end - start).toInt(), sampleRate)
        for (r in regions) {
            val src = pool[r.sourceId] ?: continue
            RegionRenderer.mix(r.copy(muted = false), src, start, out.frames, out.left, out.right)
        }
        return start to out
    }

    /**
     * "Join" (Logic: Region ▸ Join): renders the selected regions of each track into a single new
     * audio source and replaces them with one region. Returns the updated project and new sources.
     */
    fun join(project: Project, pool: SourcePool, regionIds: Set<String>): Pair<Project, List<Pair<AudioSourceInfo, AudioBuffer>>> {
        var p = project
        val created = mutableListOf<Pair<AudioSourceInfo, AudioBuffer>>()
        for (track in project.tracks) {
            val sel = track.regions.filter { it.id in regionIds }
            if (sel.size < 2) continue
            val (start, buffer) = renderRegionsDry(pool, sel, project.sampleRate)
            val id = newId()
            val info = AudioSourceInfo(id, "${sel.first().name} (joined)", buffer.frames.toLong(), project.sampleRate, "$id.wav")
            pool.put(id, buffer)
            created += info to buffer
            p = EditOps.delete(p, sel.map { it.id }.toSet())
            p = p.copy(sources = p.sources + info)
            p = EditOps.addRegion(p, track.id, Region(sourceId = id, name = info.name, start = start, sourceOffset = 0, length = buffer.frames.toLong(), color = sel.first().color))
        }
        return p to created
    }
}
