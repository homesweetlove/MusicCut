package com.musiccut.core.project

import kotlin.math.floor
import kotlin.math.roundToLong

enum class GridSize(val label: String, val beats: Double) {
    OFF("Off", 0.0),
    BAR("Bar", -1.0),
    BEAT("1/4", 1.0),
    EIGHTH("1/8", 0.5),
    SIXTEENTH("1/16", 0.25),
}

object TimeUtil {
    fun gridFrames(project: Project, grid: GridSize): Double = when (grid) {
        GridSize.OFF -> 0.0
        GridSize.BAR -> project.framesPerBar
        else -> project.framesPerBeat * grid.beats
    }

    fun snap(frame: Long, project: Project, grid: GridSize): Long {
        val g = gridFrames(project, grid)
        if (g <= 0.0) return frame
        return ((frame / g).roundToLong() * g).roundToLong().coerceAtLeast(0)
    }

    /** "bar.beat.sixteenth" position like Logic's LCD (1-based). */
    fun barsBeats(frame: Long, project: Project): String {
        val beatsTotal = frame / project.framesPerBeat
        val bar = floor(beatsTotal / project.beatsPerBar).toLong() + 1
        val beatInBar = floor(beatsTotal % project.beatsPerBar).toInt() + 1
        val sixteenth = floor((beatsTotal - floor(beatsTotal)) * 4).toInt() + 1
        return "$bar.$beatInBar.$sixteenth"
    }

    fun clock(frame: Long, sampleRate: Int): String {
        val totalMs = frame * 1000 / sampleRate
        val m = totalMs / 60000
        val s = (totalMs / 1000) % 60
        val ms = totalMs % 1000
        return "%d:%02d.%03d".format(m, s, ms)
    }
}
