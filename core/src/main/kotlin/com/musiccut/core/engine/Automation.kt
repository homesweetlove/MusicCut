package com.musiccut.core.engine

import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.AutoPoint
import com.musiccut.core.project.AutomationLane
import com.musiccut.core.project.Track
import kotlin.math.pow

/** Fader law shared by the mixer UI and volume automation: position 0..1 ↔ dB (-60..+6). */
object FaderLaw {
    const val MIN_DB = -60f
    const val MAX_DB = 6f

    fun dbToPos(db: Float): Float {
        if (db <= MIN_DB) return 0f
        val lin = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
        return lin.toDouble().pow(1.0 / 1.6).toFloat()
    }

    fun posToDb(pos: Float): Float {
        val lin = pos.coerceIn(0f, 1f).toDouble().pow(1.6).toFloat()
        return if (pos <= 0f) -120f else MIN_DB + lin * (MAX_DB - MIN_DB)
    }
}

object Automation {
    const val VOLUME = "volume"
    const val PAN = "pan"

    fun sendTarget(busId: String) = "send:$busId"
    fun pluginTarget(slotId: String, paramId: String) = "plugin:$slotId:$paramId"

    /** Linear interpolation between breakpoints; holds the first / last value outside. */
    fun valueAt(points: List<AutoPoint>, frame: Long): Float? {
        if (points.isEmpty()) return null
        if (frame <= points.first().frame) return points.first().value
        if (frame >= points.last().frame) return points.last().value
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].frame <= frame) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val t = (frame - a.frame).toFloat() / (b.frame - a.frame).coerceAtLeast(1)
        return a.value + (b.value - a.value) * t
    }

    fun lane(track: Track, target: String): AutomationLane? = track.automation.firstOrNull { it.target == target && it.enabled && it.points.isNotEmpty() }

    /** Human readable name of a target. */
    fun label(track: Track, target: String, busName: (String) -> String?): String = when {
        target == VOLUME -> "Volume"
        target == PAN -> "Pan"
        target.startsWith("send:") -> "Send → " + (busName(target.removePrefix("send:")) ?: "?")
        target.startsWith("plugin:") -> {
            val (slotId, paramId) = target.removePrefix("plugin:").split(":", limit = 2)
            val slot = track.plugins.firstOrNull { it.id == slotId }
            val d = slot?.let { PluginRegistry.find(it.pluginId) }
            "${d?.name ?: "Plugin"}: ${d?.params?.firstOrNull { it.id == paramId }?.name ?: paramId}"
        }
        else -> target
    }

    /** Normalized current (non-automated) value of a target, used as the first point of a new lane. */
    fun currentValue(track: Track, target: String): Float = when {
        target == VOLUME -> FaderLaw.dbToPos(track.volumeDb)
        target == PAN -> (track.pan + 1f) / 2f
        target.startsWith("send:") -> FaderLaw.dbToPos(track.sends.firstOrNull { it.busId == target.removePrefix("send:") }?.levelDb ?: -10f)
        target.startsWith("plugin:") -> {
            val (slotId, paramId) = target.removePrefix("plugin:").split(":", limit = 2)
            val slot = track.plugins.firstOrNull { it.id == slotId }
            val spec = slot?.let { PluginRegistry.find(it.pluginId) }?.params?.firstOrNull { it.id == paramId }
            if (spec == null) 0.5f else spec.toNormalized(slot.params[paramId] ?: spec.default)
        }
        else -> 0.5f
    }

    /** Adds / replaces a point, keeping the list sorted. Points closer than [mergeFrames] are replaced. */
    fun withPoint(points: List<AutoPoint>, point: AutoPoint, mergeFrames: Long = 0): List<AutoPoint> =
        (points.filterNot { kotlin.math.abs(it.frame - point.frame) <= mergeFrames } + point).sortedBy { it.frame }
}
