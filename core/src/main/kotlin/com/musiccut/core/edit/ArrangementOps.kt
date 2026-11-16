package com.musiccut.core.edit

import com.musiccut.core.project.ArrangementSection
import com.musiccut.core.project.Marker
import com.musiccut.core.project.Project
import com.musiccut.core.project.newId

/**
 * Song-level ("global") editing that keeps every track, the marker track and the arrangement
 * track in sync — the equivalent of Logic's arrangement-marker editing.
 */
object ArrangementOps {

    private fun allTracks(p: Project) = p.tracks.map { it.id }.toSet()

    /** Moves markers / section boundaries at or after [at] by [delta] (sections spanning [at] grow). */
    fun shiftTimeline(p: Project, at: Long, delta: Long): Project = p.copy(
        markers = p.markers.map { if (it.position >= at) it.copy(position = it.position + delta) else it },
        sections = p.sections.map { s ->
            when {
                s.start >= at -> s.copy(start = s.start + delta, end = s.end + delta)
                s.end > at -> s.copy(end = s.end + delta)
                else -> s
            }
        },
        cycleStart = if (p.cycleStart >= at) p.cycleStart + delta else p.cycleStart,
        cycleEnd = if (p.cycleEnd > at) p.cycleEnd + delta else p.cycleEnd,
    )

    /** Removes [from, to) from the whole song: audio on every track, markers and sections. */
    fun rippleCutAll(p: Project, from: Long, to: Long): Pair<Project, Clipboard> {
        if (to <= from) return p to Clipboard(emptyList(), 0)
        val len = to - from
        val (q, clip) = EditOps.rippleCut(p, allTracks(p), from, to)
        fun map(x: Long) = when {
            x <= from -> x
            x >= to -> x - len
            else -> from
        }
        val markers = q.markers.filterNot { it.position in from until to }.map { it.copy(position = map(it.position)) }
        val sections = q.sections.map { it.copy(start = map(it.start), end = map(it.end)) }.filter { it.end > it.start }
        return q.copy(
            markers = markers,
            sections = sections,
            cycleStart = map(q.cycleStart),
            cycleEnd = map(q.cycleEnd),
        ) to clip
    }

    /** Inserts audio at [at] on all tracks (pushing everything later), plus optional sections/markers. */
    fun rippleInsertAll(
        p: Project,
        clip: Clipboard,
        at: Long,
        sections: List<ArrangementSection> = emptyList(),
        markers: List<Marker> = emptyList(),
    ): Project {
        if (clip.length <= 0 || p.tracks.isEmpty()) return p
        var q = EditOps.shiftAfter(p, allTracks(p), at, clip.length)
        q = shiftTimeline(q, at, clip.length)
        q = EditOps.paste(q, clip, q.tracks.first().id, at).first
        return q.copy(
            sections = (q.sections + sections).sortedBy { it.start },
            markers = (q.markers + markers).sortedBy { it.position },
        )
    }

    fun copyAll(p: Project, from: Long, to: Long): Clipboard = EditOps.copyRange(p, allTracks(p), from, to)

    // ------------------------------------------------------------------ sections

    fun addSection(p: Project, name: String, start: Long, end: Long, color: Int = p.sections.size % 8): Project {
        if (end <= start) return p
        // a new section replaces the part of existing sections it overlaps
        val trimmed = p.sections.flatMap { s ->
            if (s.end <= start || s.start >= end) listOf(s)
            else listOfNotNull(
                if (s.start < start) s.copy(end = start) else null,
                if (s.end > end) s.copy(id = newId(), start = end) else null,
            )
        }
        return p.copy(sections = (trimmed + ArrangementSection(name = name, start = start, end = end, color = color)).sortedBy { it.start })
    }

    fun renameSection(p: Project, id: String, name: String) = p.copy(sections = p.sections.map { if (it.id == id) it.copy(name = name) else it })

    fun removeSectionMarker(p: Project, id: String) = p.copy(sections = p.sections.filterNot { it.id == id })

    /** Deletes the section and its audio on every track, closing the gap. */
    fun deleteSection(p: Project, id: String): Project {
        val s = p.sections.firstOrNull { it.id == id } ?: return p
        return rippleCutAll(p, s.start, s.end).first
    }

    /** Repeats the section right after itself (e.g. a second chorus). */
    fun duplicateSection(p: Project, id: String): Project {
        val s = p.sections.firstOrNull { it.id == id } ?: return p
        val clip = copyAll(p, s.start, s.end)
        val copy = s.copy(id = newId(), start = s.end, end = s.end + s.length)
        val len = s.length
        val base = p.copy(sections = p.sections)
        var q = EditOps.shiftAfter(base, allTracks(base), s.end, len)
        // shift everything strictly after the section (not the section itself)
        q = q.copy(
            markers = q.markers.map { if (it.position >= s.end) it.copy(position = it.position + len) else it },
            sections = q.sections.map { if (it.start >= s.end) it.copy(start = it.start + len, end = it.end + len) else it },
        )
        if (q.tracks.isNotEmpty()) q = EditOps.paste(q, clip, q.tracks.first().id, s.end).first
        return q.copy(sections = (q.sections + copy).sortedBy { it.start })
    }

    /** Swaps the section with its previous ([direction] = -1) or next (+1) neighbour. */
    fun moveSection(p: Project, id: String, direction: Int): Project {
        val sorted = p.sections.sortedBy { it.start }
        val i = sorted.indexOfFirst { it.id == id }
        val j = i + direction
        if (i < 0 || j !in sorted.indices) return p
        val s = sorted[i]
        val other = sorted[j]
        val clip = copyAll(p, s.start, s.end)
        val (cut, _) = rippleCutAll(p, s.start, s.end)
        val insertAt = if (direction < 0) other.start else other.end - s.length
        val moved = s.copy(start = insertAt, end = insertAt + s.length)
        val sectionMarkers = p.markers.filter { it.position in s.start until s.end }
            .map { it.copy(position = it.position - s.start + insertAt) }
        return rippleInsertAll(cut, clip, insertAt, listOf(moved), sectionMarkers)
    }

    // ------------------------------------------------------------------ markers

    fun addMarker(p: Project, position: Long, name: String? = null): Project =
        p.copy(markers = (p.markers + Marker(name = name ?: "Marker ${p.markers.size + 1}", position = position)).sortedBy { it.position })

    fun removeMarker(p: Project, id: String) = p.copy(markers = p.markers.filterNot { it.id == id })

    fun renameMarker(p: Project, id: String, name: String) = p.copy(markers = p.markers.map { if (it.id == id) it.copy(name = name) else it })

    fun moveMarker(p: Project, id: String, position: Long) =
        p.copy(markers = p.markers.map { if (it.id == id) it.copy(position = position.coerceAtLeast(0)) else it }.sortedBy { it.position })
}
