package com.musiccut.core.edit

import com.musiccut.core.project.Project
import com.musiccut.core.project.Region
import com.musiccut.core.project.TakeLane
import com.musiccut.core.project.newId

/**
 * Take folders and comping: every recording pass over existing material becomes a take lane;
 * the track's main lane is the comp, built by choosing sections from the takes.
 */
object TakeOps {

    /**
     * Records [newRegion] onto [trackId] as a new take. If it overlaps existing material, that
     * material is preserved as a take lane first (so nothing recorded is ever lost).
     */
    fun addTake(project: Project, trackId: String, newRegion: Region): Project {
        val track = project.track(trackId) ?: return project
        val overlapped = track.regions.filter { it.overlaps(newRegion.start, newRegion.end) }
        var lanes = track.takeLanes
        if (overlapped.isNotEmpty() && lanes.isEmpty()) {
            val clip = EditOps.copyRange(project, setOf(trackId), newRegion.start, newRegion.end)
            val preserved = clip.items.map { it.region.copy(id = newId(), start = it.region.start + newRegion.start) }
            if (preserved.isNotEmpty()) lanes = lanes + TakeLane(name = "Take 1", regions = preserved)
        }
        if (overlapped.isNotEmpty() || lanes.isNotEmpty()) {
            lanes = lanes + TakeLane(name = "Take ${lanes.size + 1}", regions = listOf(newRegion.copy(id = newId())))
        }
        val withLanes = project.updateTrack(trackId) { it.copy(takeLanes = lanes) }
        return EditOps.addRegion(withLanes, trackId, newRegion)
    }

    /** Copies [from, to) of a take lane into the comp (main lane), replacing what is there. */
    fun compRange(project: Project, trackId: String, laneId: String, from: Long, to: Long): Project {
        val track = project.track(trackId) ?: return project
        val lane = track.takeLanes.firstOrNull { it.id == laneId } ?: return project
        var p = EditOps.clearRange(project, setOf(trackId), from, to)
        for (r in lane.regions) {
            if (!r.overlaps(from, to)) continue
            val piece = EditOps.subRegion(project, r, maxOf(r.start, from), minOf(r.end, to)) ?: continue
            p = p.updateTrack(trackId) { t -> t.copy(regions = (t.regions + piece).sortedBy { it.start }) }
        }
        return p
    }

    /** Uses a whole take as the comp for its time span. */
    fun compWholeTake(project: Project, trackId: String, laneId: String): Project {
        val lane = project.track(trackId)?.takeLanes?.firstOrNull { it.id == laneId } ?: return project
        if (lane.regions.isEmpty()) return project
        return compRange(project, trackId, laneId, lane.regions.minOf { it.start }, lane.regions.maxOf { it.end })
    }

    fun deleteTake(project: Project, trackId: String, laneId: String): Project =
        project.updateTrack(trackId) { t -> t.copy(takeLanes = t.takeLanes.filterNot { it.id == laneId }) }

    /** Flattens: forget the takes, keep the comp. */
    fun flatten(project: Project, trackId: String): Project = project.updateTrack(trackId) { it.copy(takeLanes = emptyList()) }
}
