package com.musiccut.core.edit

import com.musiccut.core.project.Project
import com.musiccut.core.project.Region
import com.musiccut.core.project.Track
import com.musiccut.core.project.newId

/** Regions copied to the clipboard. Region starts are relative to the clipboard origin (0). */
data class Clipboard(val items: List<ClipItem>, val length: Long) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/** [trackOffset] is the lane offset relative to the top-most copied track. */
data class ClipItem(val trackOffset: Int, val region: Region)

/**
 * Pure, non-destructive editing operations. Every function returns a new [Project]; audio data is
 * never touched, so all of this is cheap and fully undoable.
 */
object EditOps {

    // ---------------------------------------------------------------- region geometry

    /**
     * Returns the part of [r] visible on the timeline between [from] and [to]. Works for extension
     * too (from < start / to > end), clamped to the underlying source. Returns null if empty.
     */
    fun subRegion(project: Project, r: Region, from: Long, to: Long, keepId: Boolean = false): Region? {
        val srcFrames = project.source(r.sourceId)?.frames ?: (r.sourceOffset + r.length)
        var f = from
        var t = to
        val newOffset: Long
        if (!r.reversed) {
            var off = r.sourceOffset + (f - r.start)
            if (off < 0) { f -= off; off = 0 }
            val maxLen = srcFrames - off
            if (t - f > maxLen) t = f + maxLen
            newOffset = off
        } else {
            // timeline [f,t) maps to source [off + end - t, off + end - f)
            val base = r.sourceOffset + r.end
            if (base - t < 0) t = base
            if (base - f > srcFrames) f = base - srcFrames
            newOffset = base - t
        }
        if (t <= f) return null
        val len = t - f
        return r.copy(
            id = if (keepId) r.id else newId(),
            start = f,
            sourceOffset = newOffset,
            length = len,
            fadeIn = if (f == r.start) r.fadeIn.coerceAtMost(len) else 0,
            fadeOut = if (t == r.end) r.fadeOut.coerceAtMost(len) else 0,
        )
    }

    private fun mapRegions(project: Project, f: (Track, Region) -> List<Region>): Project =
        project.copy(tracks = project.tracks.map { t -> t.copy(regions = t.regions.flatMap { f(t, it) }.sortedBy { it.start }) })

    // ---------------------------------------------------------------- tracks

    fun addTrack(project: Project, name: String? = null, index: Int = project.tracks.size): Pair<Project, Track> {
        val track = Track(name = name ?: "Audio ${project.tracks.size + 1}", color = project.tracks.size % 8)
        val list = project.tracks.toMutableList().apply { add(index.coerceIn(0, size), track) }
        return project.copy(tracks = list) to track
    }

    fun removeTrack(project: Project, trackId: String) = project.copy(tracks = project.tracks.filterNot { it.id == trackId })

    fun duplicateTrack(project: Project, trackId: String): Project {
        val idx = project.tracks.indexOfFirst { it.id == trackId }
        if (idx < 0) return project
        val t = project.tracks[idx]
        val copy = t.copy(
            id = newId(), name = "${t.name} copy", solo = false, armed = false,
            regions = t.regions.map { it.copy(id = newId()) },
            plugins = t.plugins.map { it.copy(id = newId()) },
        )
        return project.copy(tracks = project.tracks.toMutableList().apply { add(idx + 1, copy) })
    }

    fun moveTrack(project: Project, trackId: String, delta: Int): Project {
        val idx = project.tracks.indexOfFirst { it.id == trackId }
        val to = (idx + delta).coerceIn(0, project.tracks.size - 1)
        if (idx < 0 || idx == to) return project
        val list = project.tracks.toMutableList()
        val t = list.removeAt(idx)
        list.add(to, t)
        return project.copy(tracks = list)
    }

    // ---------------------------------------------------------------- regions

    fun addRegion(project: Project, trackId: String, region: Region, overwrite: Boolean = true): Project {
        var p = project
        if (overwrite) p = clearRange(p, setOf(trackId), region.start, region.end)
        return p.updateTrack(trackId) { t -> t.copy(regions = (t.regions + region).sortedBy { it.start }) }
    }

    fun updateRegion(project: Project, regionId: String, f: (Region) -> Region): Project =
        project.copy(tracks = project.tracks.map { t ->
            if (t.regions.none { it.id == regionId }) t else t.copy(regions = t.regions.map { if (it.id == regionId) f(it) else it })
        })

    /** Splits every region in [regionIds] (or every region on every track if null) that spans [at]. */
    fun split(project: Project, at: Long, regionIds: Set<String>? = null): Project = mapRegions(project) { _, r ->
        if ((regionIds == null || r.id in regionIds) && at > r.start && at < r.end) {
            listOfNotNull(subRegion(project, r, r.start, at, keepId = true), subRegion(project, r, at, r.end))
        } else listOf(r)
    }

    fun delete(project: Project, regionIds: Set<String>): Project =
        project.copy(tracks = project.tracks.map { t -> t.copy(regions = t.regions.filterNot { it.id in regionIds }) })

    fun copy(project: Project, regionIds: Set<String>): Clipboard {
        val picked = project.tracks.withIndex().flatMap { (i, t) -> t.regions.filter { it.id in regionIds }.map { i to it } }
        if (picked.isEmpty()) return Clipboard(emptyList(), 0)
        val origin = picked.minOf { it.second.start }
        val topTrack = picked.minOf { it.first }
        val end = picked.maxOf { it.second.end }
        return Clipboard(picked.map { (ti, r) -> ClipItem(ti - topTrack, r.copy(start = r.start - origin)) }, end - origin)
    }

    fun cut(project: Project, regionIds: Set<String>): Pair<Project, Clipboard> =
        delete(project, regionIds) to copy(project, regionIds)

    /**
     * Pastes [clip] at [at] starting from [trackId]. Existing material under the pasted regions is
     * replaced (like Logic's "Paste Replace"). Returns the ids of the new regions.
     */
    fun paste(project: Project, clip: Clipboard, trackId: String, at: Long): Pair<Project, Set<String>> {
        val base = project.tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        var p = project
        val ids = mutableSetOf<String>()
        for (item in clip.items) {
            var idx = base + item.trackOffset
            while (idx >= p.tracks.size) p = addTrack(p).first
            idx = idx.coerceAtLeast(0)
            val region = item.region.copy(id = newId(), start = at + item.region.start)
            p = addRegion(p, p.tracks[idx].id, region)
            ids += region.id
        }
        return p to ids
    }

    /** Moves a region to [newStart] on [newTrackId], replacing whatever it lands on. */
    fun move(project: Project, regionId: String, newStart: Long, newTrackId: String? = null): Project {
        val (track, region) = project.findRegion(regionId) ?: return project
        val moved = region.copy(start = newStart.coerceAtLeast(0))
        val removed = project.updateTrack(track.id) { t -> t.copy(regions = t.regions.filterNot { it.id == regionId }) }
        return addRegion(removed, newTrackId ?: track.id, moved)
    }

    /** Moves several regions by the same delta (keeping them on their tracks). */
    fun moveMany(project: Project, regionIds: Set<String>, delta: Long, trackDelta: Int = 0): Project {
        var p = project
        val minStart = regionIds.mapNotNull { p.findRegion(it)?.second?.start }.minOrNull() ?: return p
        val d = if (minStart + delta < 0) -minStart else delta
        val moves = regionIds.mapNotNull { id ->
            val (t, r) = project.findRegion(id) ?: return@mapNotNull null
            val ti = project.tracks.indexOf(t)
            val target = project.tracks.getOrNull((ti + trackDelta).coerceIn(0, project.tracks.size - 1))?.id ?: t.id
            Triple(r, t.id, target)
        }
        p = delete(p, regionIds)
        for ((r, _, target) in moves) p = addRegion(p, target, r.copy(start = r.start + d))
        return p
    }

    fun trimStart(project: Project, regionId: String, newStart: Long): Project {
        val (track, r) = project.findRegion(regionId) ?: return project
        val s = newStart.coerceIn(0, r.end - 1)
        val nr = subRegion(project, r, s, r.end, keepId = true)?.copy(fadeIn = r.fadeIn.coerceAtMost(r.end - s)) ?: return project
        return project.updateTrack(track.id) { t -> t.copy(regions = t.regions.map { if (it.id == regionId) nr else it }.sortedBy { it.start }) }
    }

    fun trimEnd(project: Project, regionId: String, newEnd: Long): Project {
        val (track, r) = project.findRegion(regionId) ?: return project
        val e = newEnd.coerceAtLeast(r.start + 1)
        val nr = subRegion(project, r, r.start, e, keepId = true)?.copy(fadeOut = r.fadeOut.coerceAtMost(e - r.start)) ?: return project
        return project.updateTrack(track.id) { t -> t.copy(regions = t.regions.map { if (it.id == regionId) nr else it }) }
    }

    fun setFades(project: Project, regionId: String, fadeIn: Long?, fadeOut: Long?): Project = updateRegion(project, regionId) {
        it.copy(
            fadeIn = (fadeIn ?: it.fadeIn).coerceIn(0, it.length),
            fadeOut = (fadeOut ?: it.fadeOut).coerceIn(0, it.length),
        )
    }

    fun setGain(project: Project, regionIds: Set<String>, gainDb: Float): Project =
        regionIds.fold(project) { p, id -> updateRegion(p, id) { it.copy(gainDb = gainDb.coerceIn(-60f, 24f)) } }

    fun toggleMute(project: Project, regionIds: Set<String>): Project =
        regionIds.fold(project) { p, id -> updateRegion(p, id) { it.copy(muted = !it.muted) } }

    fun reverse(project: Project, regionIds: Set<String>): Project =
        regionIds.fold(project) { p, id -> updateRegion(p, id) { it.copy(reversed = !it.reversed, fadeIn = it.fadeOut, fadeOut = it.fadeIn) } }

    fun rename(project: Project, regionId: String, name: String) = updateRegion(project, regionId) { it.copy(name = name) }

    /** Duplicates the selection right after itself (Logic's "Repeat"). */
    fun duplicate(project: Project, regionIds: Set<String>): Pair<Project, Set<String>> {
        val clip = copy(project, regionIds)
        if (clip.isEmpty) return project to emptySet()
        val picked = project.tracks.withIndex().filter { (_, t) -> t.regions.any { it.id in regionIds } }
        val topTrack = project.tracks[picked.minOf { it.index }]
        val origin = regionIds.mapNotNull { project.findRegion(it)?.second?.start }.min()
        return paste(project, clip, topTrack.id, origin + clip.length)
    }

    // ---------------------------------------------------------------- range (marquee) editing

    /** Removes audio between [from] and [to] on [trackIds] without moving anything else. */
    fun clearRange(project: Project, trackIds: Set<String>, from: Long, to: Long): Project {
        if (to <= from) return project
        return mapRegions(project) { t, r ->
            if (t.id !in trackIds || !r.overlaps(from, to)) listOf(r)
            else listOfNotNull(
                if (r.start < from) subRegion(project, r, r.start, from, keepId = true) else null,
                if (r.end > to) subRegion(project, r, to, r.end) else null,
            )
        }
    }

    fun copyRange(project: Project, trackIds: Set<String>, from: Long, to: Long): Clipboard {
        val tracks = project.tracks.withIndex().filter { it.value.id in trackIds }
        if (tracks.isEmpty() || to <= from) return Clipboard(emptyList(), 0)
        val top = tracks.minOf { it.index }
        val items = tracks.flatMap { (i, t) ->
            t.regions.filter { it.overlaps(from, to) }.mapNotNull { r ->
                subRegion(project, r, maxOf(r.start, from), minOf(r.end, to))?.let { ClipItem(i - top, it.copy(start = it.start - from)) }
            }
        }
        return Clipboard(items, to - from)
    }

    /** Shifts all material on [trackIds] starting at/after [at] by [delta] frames. */
    fun shiftAfter(project: Project, trackIds: Set<String>, at: Long, delta: Long): Project {
        val p = split(project, at, project.tracks.filter { it.id in trackIds }.flatMap { t -> t.regions.map { it.id } }.toSet())
        return p.copy(tracks = p.tracks.map { t ->
            if (t.id !in trackIds) t else t.copy(regions = t.regions.map { if (it.start >= at) it.copy(start = (it.start + delta).coerceAtLeast(0)) else it })
        })
    }

    /**
     * Cuts the time range out of the song and closes the gap (ripple delete) — the most common
     * "remove this part of the song" operation.
     */
    fun rippleCut(project: Project, trackIds: Set<String>, from: Long, to: Long): Pair<Project, Clipboard> {
        val clip = copyRange(project, trackIds, from, to)
        val cleared = clearRange(project, trackIds, from, to)
        return shiftAfter(cleared, trackIds, to, -(to - from)) to clip
    }

    /** Inserts clipboard at [at], pushing later material to the right (ripple insert). */
    fun rippleInsert(project: Project, clip: Clipboard, trackId: String, at: Long): Pair<Project, Set<String>> {
        if (clip.isEmpty) return project to emptySet()
        val base = project.tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        val maxOffset = clip.items.maxOf { it.trackOffset }
        var p = project
        while (p.tracks.size <= base + maxOffset) p = addTrack(p).first
        val affected = (base..base + maxOffset).map { p.tracks[it].id }.toSet()
        p = shiftAfter(p, affected, at, clip.length)
        return paste(p, clip, p.tracks[base].id, at)
    }

    fun insertSilence(project: Project, trackIds: Set<String>, at: Long, length: Long): Project =
        shiftAfter(project, trackIds, at, length)

    /** Keeps only the range, moving it to the project start. */
    fun cropToRange(project: Project, trackIds: Set<String>, from: Long, to: Long): Project {
        var p = clearRange(project, trackIds, to, Long.MAX_VALUE / 4)
        p = clearRange(p, trackIds, 0, from)
        return p.copy(tracks = p.tracks.map { t ->
            if (t.id !in trackIds) t else t.copy(regions = t.regions.map { it.copy(start = it.start - from) })
        })
    }

    /** Regions that are contiguous in both timeline and source are merged into one (lossless glue). */
    fun glueContiguous(project: Project, regionIds: Set<String>): Project = project.copy(tracks = project.tracks.map { t ->
        val out = mutableListOf<Region>()
        for (r in t.regions.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && last.id in regionIds && r.id in regionIds && !last.reversed && !r.reversed &&
                last.sourceId == r.sourceId && last.end == r.start && last.sourceOffset + last.length == r.sourceOffset &&
                last.gainDb == r.gainDb && last.muted == r.muted
            ) {
                out[out.size - 1] = last.copy(length = last.length + r.length, fadeOut = r.fadeOut)
            } else out += r
        }
        t.copy(regions = out)
    })

    // ---------------------------------------------------------------- crossfades

    /**
     * Crossfades every pair of touching / overlapping regions among [regionIds] on the same track:
     * both regions are extended by half of [lengthFrames] into each other (as far as their source
     * allows) and faded with complementary curves.
     */
    fun crossfade(project: Project, regionIds: Set<String>, lengthFrames: Long): Project {
        var p = project
        for (t in project.tracks) {
            val sel = t.regions.filter { it.id in regionIds }.sortedBy { it.start }
            for (i in 0 until sel.size - 1) {
                val a = p.findRegion(sel[i].id)?.second ?: continue
                val b = p.findRegion(sel[i + 1].id)?.second ?: continue
                if (b.start - a.end > 0) continue // gap: nothing to crossfade
                val half = lengthFrames / 2
                val joint = b.start
                val newA = subRegion(p, a, a.start, maxOf(a.end, joint + half), keepId = true) ?: continue
                val newB = subRegion(p, b, minOf(b.start, joint - half), b.end, keepId = true) ?: continue
                val overlap = (newA.end - newB.start).coerceAtLeast(0)
                val fa = newA.copy(fadeOut = overlap)
                val fb = newB.copy(fadeIn = overlap)
                p = updateRegion(updateRegion(p, a.id) { fa }, b.id) { fb }
            }
        }
        return p.copy(tracks = p.tracks.map { it.copy(regions = it.regions.sortedBy { r -> r.start }) })
    }
}
