package com.musiccut.core.engine

import com.musiccut.core.project.Project
import kotlin.math.PI
import kotlin.math.exp

import kotlin.math.sin

/** Logic-style click: accented downbeat, synthesized (no samples needed). */
class Metronome(private val sampleRate: Int) {
    private var clickPos = Int.MAX_VALUE
    private var freq = 1000.0
    private var amp = 0f
    private val clickLen = (sampleRate * 0.035).toInt()

    /** Adds clicks for timeline frames [start, start+frames) into the buffers. */
    fun render(project: Project, start: Long, frames: Int, outL: FloatArray, outR: FloatArray, volume: Float = 0.5f, offset: Int = 0) {
        val fpb = project.framesPerBeat
        var nextBeat = kotlin.math.ceil(start / fpb).toLong()
        var nextBeatFrame = (nextBeat * fpb).toLong()
        for (i in 0 until frames) {
            val t = start + i
            if (t >= nextBeatFrame) {
                val accent = Math.floorMod(nextBeat, project.beatsPerBar.toLong()) == 0L
                freq = if (accent) 1760.0 else 1175.0
                amp = if (accent) 1f else 0.6f
                clickPos = 0
                nextBeat++
                nextBeatFrame = (nextBeat * fpb).toLong()
            }
            if (clickPos < clickLen) {
                val x = clickPos.toDouble() / sampleRate
                val v = (sin(2 * PI * freq * x) * exp(-x * 140.0) * amp * volume).toFloat()
                outL[offset + i] += v
                outR[offset + i] += v
                clickPos++
            }
        }
    }

    fun reset() {
        clickPos = Int.MAX_VALUE
    }

}
