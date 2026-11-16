package com.musiccut.core.dsp

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** YIN fundamental-frequency estimator (de Cheveigné & Kawahara). */
class PitchDetector(private val sampleRate: Int, val windowSize: Int = 2048, private val threshold: Float = 0.15f) {
    private val diff = FloatArray(windowSize / 2)

    /** Returns the pitch in Hz of [x] starting at [offset] ([windowSize] samples), or -1 if unvoiced. */
    fun detect(x: FloatArray, offset: Int = 0, minHz: Float = 60f, maxHz: Float = 1200f): Float {
        val w = windowSize / 2
        if (offset + windowSize > x.size) return -1f
        var energy = 0f
        for (i in 0 until windowSize) energy += x[offset + i] * x[offset + i]
        if (energy / windowSize < 1e-6f) return -1f
        val minTau = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val maxTau = (sampleRate / minHz).toInt().coerceAtMost(w - 1)
        for (tau in 0 until w) {
            var s = 0f
            for (i in 0 until w) {
                val d = x[offset + i] - x[offset + i + tau]
                s += d * d
            }
            diff[tau] = s
        }
        // cumulative mean normalized difference
        diff[0] = 1f
        var running = 0f
        for (tau in 1 until w) {
            running += diff[tau]
            diff[tau] = if (running > 0) diff[tau] * tau / running else 1f
        }
        var tau = minTau
        while (tau < maxTau) {
            if (diff[tau] < threshold) {
                while (tau + 1 < maxTau && diff[tau + 1] < diff[tau]) tau++
                // parabolic interpolation
                val a = diff[tau - 1]; val b = diff[tau]; val c = diff[tau + 1]
                val denom = a - 2 * b + c
                val shift = if (denom != 0f) 0.5f * (a - c) / denom else 0f
                return sampleRate / (tau + shift)
            }
            tau++
        }
        return -1f
    }

    companion object {
        val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun hzToMidi(hz: Float): Float = 69f + 12f * (ln(hz / 440f) / ln(2f))
        fun midiToHz(midi: Float): Float = 440f * 2f.pow((midi - 69f) / 12f)
        fun noteName(midi: Float): String {
            val n = midi.roundToInt()
            return NOTE_NAMES[((n % 12) + 12) % 12] + (n / 12 - 1)
        }

        /** Scale masks: which pitch classes are allowed (relative to the key). */
        val SCALES: Map<String, IntArray> = linkedMapOf(
            "Chromatic" to intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            "Major" to intArrayOf(0, 2, 4, 5, 7, 9, 11),
            "Minor" to intArrayOf(0, 2, 3, 5, 7, 8, 10),
            "Pentatonic" to intArrayOf(0, 2, 4, 7, 9),
            "Blues" to intArrayOf(0, 3, 5, 6, 7, 10),
        )

        /** Nearest allowed MIDI note for [midi] in the given key (0 = C) and scale. */
        fun snap(midi: Float, key: Int, scale: IntArray): Float {
            val base = kotlin.math.floor(midi).toInt()
            var best = base.toFloat()
            var bestDist = Float.MAX_VALUE
            for (n in base - 12..base + 12) {
                val pc = ((n - key) % 12 + 12) % 12
                if (pc !in scale) continue
                val d = kotlin.math.abs(n - midi)
                if (d < bestDist) { bestDist = d; best = n.toFloat() }
            }
            return best
        }
    }
}

/** A detected monophonic note (Flex Pitch). */
data class PitchNote(val start: Int, val end: Int, val midi: Float)

object NoteSegmenter {
    /** Tracks pitch every [hop] samples and groups stable regions into notes. */
    fun detectNotes(x: FloatArray, sampleRate: Int, hop: Int = 512): List<PitchNote> {
        val det = PitchDetector(sampleRate)
        val pitches = ArrayList<Float>()
        var pos = 0
        while (pos + det.windowSize <= x.size) {
            val hz = det.detect(x, pos)
            pitches += if (hz > 0) PitchDetector.hzToMidi(hz) else Float.NaN
            pos += hop
        }
        val notes = ArrayList<PitchNote>()
        var startFrame = -1
        var sum = 0f
        var count = 0
        fun close(endFrame: Int) {
            if (startFrame >= 0 && count >= 4) {
                notes += PitchNote(startFrame * hop, endFrame * hop + det.windowSize / 2, sum / count)
            }
            startFrame = -1; sum = 0f; count = 0
        }
        for (i in pitches.indices) {
            val p = pitches[i]
            if (p.isNaN()) { close(i); continue }
            if (startFrame >= 0 && kotlin.math.abs(p - sum / count) > 0.7f) close(i)
            if (startFrame < 0) startFrame = i
            sum += p; count++
        }
        close(pitches.size)
        return notes
    }
}
