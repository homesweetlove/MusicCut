package com.musiccut.core.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().substring(0, 12)

/**
 * Metadata for a plug-in that originated outside MusicCut (Logic native/AU, future VST bridges,
 * etc). The host may map it to a compatible MusicCut effect; when it cannot execute the original
 * plug-in, this record keeps its identity and opaque state so saving/reopening never loses it.
 */
@Serializable
data class ExternalPluginRef(
    val format: String,
    val name: String,
    val manufacturer: String? = null,
    val type: String? = null,
    val subtype: String? = null,
    val presetName: String? = null,
    val opaqueStateBase64: String? = null,
) {
    val fingerprint: String?
        get() = listOfNotNull(type, subtype, manufacturer)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("/")
}

/** Records where an imported project came from and any best-effort conversion warnings. */
@Serializable
data class ProjectImportInfo(
    val sourceFormat: String,
    val sourceName: String,
    val warnings: List<String> = emptyList(),
)

/** An insert-effect slot on a track or the master bus. */
@Serializable
data class PluginSlot(
    val id: String = newId(),
    val pluginId: String,
    val params: Map<String, Float>,
    val bypassed: Boolean = false,
    /** Track whose (pre-insert) signal keys this plugin's detector (Logic "Side Chain"). */
    val sidechainTrackId: String? = null,
    /** Original external plug-in metadata/state, if this slot came from another DAW. */
    val external: ExternalPluginRef? = null,
)

/** One breakpoint of an automation curve; [value] is normalized 0..1. */
@Serializable
data class AutoPoint(val frame: Long, val value: Float)

/**
 * Automation of one parameter. [target] is "volume", "pan", "send:<busId>" or
 * "plugin:<slotId>:<paramId>".
 */
@Serializable
data class AutomationLane(
    val id: String = newId(),
    val target: String,
    val points: List<AutoPoint> = emptyList(),
    val enabled: Boolean = true,
)

/** A recorded take kept for comping (Logic take folders). */
@Serializable
data class TakeLane(
    val id: String = newId(),
    val name: String,
    val regions: List<Region> = emptyList(),
)

/** Imported / recorded audio file referenced by regions. Audio data lives in a [SourcePool]. */
@Serializable
data class AudioSourceInfo(
    val id: String,
    val name: String,
    val frames: Long,
    val sampleRate: Int,
    /** File name inside the project folder (WAV). */
    val fileName: String,
)

/**
 * A non-destructive window onto an [AudioSourceInfo], placed on the timeline. All positions are in
 * project sample frames.
 */
@Serializable
data class Region(
    val id: String = newId(),
    val sourceId: String,
    val name: String,
    val start: Long,
    val sourceOffset: Long,
    val length: Long,
    val gainDb: Float = 0f,
    val fadeIn: Long = 0,
    val fadeOut: Long = 0,
    val reversed: Boolean = false,
    val muted: Boolean = false,
    val color: Int = 0,
) {
    val end: Long get() = start + length
    fun contains(frame: Long) = frame in start until end
    fun overlaps(from: Long, to: Long) = start < to && end > from
}

/** Post- or pre-fader send from a track to an aux bus (Logic "Sends"). */
@Serializable
data class Send(
    val busId: String,
    val levelDb: Float = -10f,
    val preFader: Boolean = false,
)

/** Aux channel strip fed by track sends, e.g. a shared reverb or delay return. */
@Serializable
data class AuxBus(
    val id: String = newId(),
    val name: String,
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val plugins: List<PluginSlot> = emptyList(),
    val color: Int = 7,
)

/** Named position on the marker track. */
@Serializable
data class Marker(
    val id: String = newId(),
    val name: String,
    val position: Long,
    val color: Int = 2,
)

/** Logic "Arrangement" marker: a song section (Intro, Verse, Chorus…) that can be moved as a whole. */
@Serializable
data class ArrangementSection(
    val id: String = newId(),
    val name: String,
    val start: Long,
    val end: Long,
    val color: Int = 0,
) {
    val length: Long get() = end - start
}

@Serializable
data class Track(
    val id: String = newId(),
    val name: String,
    val regions: List<Region> = emptyList(),
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val armed: Boolean = false,
    val plugins: List<PluginSlot> = emptyList(),
    val sends: List<Send> = emptyList(),
    val color: Int = 0,
    val automation: List<AutomationLane> = emptyList(),
    /** Aux bus this track feeds instead of the Stereo Out (summing track stacks). */
    val outputBusId: String? = null,
    /** Tracks with the same group move together (volume / mute / solo). */
    val groupId: Int? = null,
    val takeLanes: List<TakeLane> = emptyList(),
)

@Serializable
data class Project(
    val name: String = "Untitled",
    val sampleRate: Int = 44100,
    val tempo: Float = 120f,
    val beatsPerBar: Int = 4,
    val tracks: List<Track> = emptyList(),
    val sources: List<AudioSourceInfo> = emptyList(),
    val masterVolumeDb: Float = 0f,
    val masterPlugins: List<PluginSlot> = emptyList(),
    val cycleStart: Long = 0,
    val cycleEnd: Long = 0,
    val cycleEnabled: Boolean = false,
    val buses: List<AuxBus> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val sections: List<ArrangementSection> = emptyList(),
    val keySignature: String = "C major",
    val importInfo: ProjectImportInfo? = null,
) {
    val framesPerBeat: Double get() = sampleRate * 60.0 / tempo
    val framesPerBar: Double get() = framesPerBeat * beatsPerBar

    /** End of the last region, i.e. the natural song length. */
    val contentEnd: Long get() = tracks.maxOfOrNull { t -> t.regions.maxOfOrNull { it.end } ?: 0L } ?: 0L

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
    fun source(id: String): AudioSourceInfo? = sources.firstOrNull { it.id == id }

    fun findRegion(regionId: String): Pair<Track, Region>? {
        for (t in tracks) for (r in t.regions) if (r.id == regionId) return t to r
        return null
    }

    fun bus(id: String): AuxBus? = buses.firstOrNull { it.id == id }
    fun updateBus(id: String, f: (AuxBus) -> AuxBus) = copy(buses = buses.map { if (it.id == id) f(it) else it })

    fun updateTrack(id: String, f: (Track) -> Track) = copy(tracks = tracks.map { if (it.id == id) f(it) else it })

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        fun fromJson(text: String): Project = json.decodeFromString(serializer(), text)
    }
}
