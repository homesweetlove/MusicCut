package com.musiccut.core.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Non-interleaved stereo float audio. Mono material is stored with both channels equal so the
 * engine never has to special-case channel counts.
 */
class AudioBuffer(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
    init {
        require(left.size == right.size) { "channel length mismatch" }
    }

    val frames: Int get() = left.size
    val durationSeconds: Double get() = frames.toDouble() / sampleRate

    fun copyRange(start: Int, end: Int): AudioBuffer {
        val s = start.coerceIn(0, frames)
        val e = end.coerceIn(s, frames)
        return AudioBuffer(left.copyOfRange(s, e), right.copyOfRange(s, e), sampleRate)
    }

    fun peak(): Float {
        var p = 0f
        for (i in 0 until frames) {
            p = max(p, max(abs(left[i]), abs(right[i])))
        }
        return p
    }

    companion object {
        fun silent(frames: Int, sampleRate: Int) = AudioBuffer(FloatArray(frames), FloatArray(frames), sampleRate)

        fun fromInterleaved(data: FloatArray, channels: Int, sampleRate: Int): AudioBuffer {
            require(channels >= 1)
            val frames = data.size / channels
            val l = FloatArray(frames)
            val r = FloatArray(frames)
            for (i in 0 until frames) {
                l[i] = data[i * channels]
                r[i] = if (channels > 1) data[i * channels + 1] else l[i]
            }
            return AudioBuffer(l, r, sampleRate)
        }

        /** Concatenates buffers of the same sample rate. */
        fun concat(parts: List<AudioBuffer>): AudioBuffer {
            require(parts.isNotEmpty())
            val sr = parts.first().sampleRate
            val total = parts.sumOf { it.frames }
            val l = FloatArray(total)
            val r = FloatArray(total)
            var pos = 0
            for (p in parts) {
                require(p.sampleRate == sr) { "sample rate mismatch" }
                p.left.copyInto(l, pos)
                p.right.copyInto(r, pos)
                pos += p.frames
            }
            return AudioBuffer(l, r, sr)
        }
    }
}

/** Growable stereo buffer used by the recorder and offline renderers. */
class GrowableAudioBuffer(private val sampleRate: Int, initialCapacity: Int = 1 shl 16) {
    private var l = FloatArray(initialCapacity)
    private var r = FloatArray(initialCapacity)
    var frames = 0
        private set

    fun append(left: FloatArray, right: FloatArray, count: Int) {
        ensure(frames + count)
        left.copyInto(l, frames, 0, count)
        right.copyInto(r, frames, 0, count)
        frames += count
    }

    fun appendInterleaved(data: FloatArray, count: Int, channels: Int) {
        val n = count / channels
        ensure(frames + n)
        for (i in 0 until n) {
            l[frames + i] = data[i * channels]
            r[frames + i] = if (channels > 1) data[i * channels + 1] else data[i * channels]
        }
        frames += n
    }

    private fun ensure(cap: Int) {
        if (cap <= l.size) return
        val newCap = max(cap, min(l.size * 2L, Int.MAX_VALUE.toLong() - 8).toInt())
        l = l.copyOf(newCap)
        r = r.copyOf(newCap)
    }

    fun toBuffer(): AudioBuffer = AudioBuffer(l.copyOf(frames), r.copyOf(frames), sampleRate)
}
