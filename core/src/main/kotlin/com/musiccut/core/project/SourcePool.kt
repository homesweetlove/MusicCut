package com.musiccut.core.project

import com.musiccut.core.audio.AudioBuffer
import java.util.concurrent.ConcurrentHashMap

/** Holds decoded audio for every source referenced by the project (shared with the audio thread). */
class SourcePool {
    private val buffers = ConcurrentHashMap<String, AudioBuffer>()
    private val peaks = ConcurrentHashMap<String, WaveformPeaks>()

    operator fun get(id: String): AudioBuffer? = buffers[id]

    fun put(id: String, buffer: AudioBuffer) {
        buffers[id] = buffer
        peaks[id] = WaveformPeaks.compute(buffer)
    }

    fun peaks(id: String): WaveformPeaks? = peaks[id]

    fun remove(id: String) {
        buffers.remove(id)
        peaks.remove(id)
    }

    fun ids(): Set<String> = buffers.keys

    fun clear() {
        buffers.clear()
        peaks.clear()
    }
}

/** Min/max peaks at a fixed decimation, used to draw waveforms at any zoom level quickly. */
class WaveformPeaks(val samplesPerPeak: Int, val min: FloatArray, val max: FloatArray) {
    val size: Int get() = min.size

    companion object {
        const val DEFAULT_DECIMATION = 256

        fun compute(buffer: AudioBuffer, samplesPerPeak: Int = DEFAULT_DECIMATION): WaveformPeaks {
            val n = (buffer.frames + samplesPerPeak - 1) / samplesPerPeak
            val mn = FloatArray(n)
            val mx = FloatArray(n)
            for (p in 0 until n) {
                var lo = 0f
                var hi = 0f
                val s = p * samplesPerPeak
                val e = minOf(buffer.frames, s + samplesPerPeak)
                for (i in s until e) {
                    val v = (buffer.left[i] + buffer.right[i]) * 0.5f
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                mn[p] = lo
                mx[p] = hi
            }
            return WaveformPeaks(samplesPerPeak, mn, mx)
        }
    }
}
