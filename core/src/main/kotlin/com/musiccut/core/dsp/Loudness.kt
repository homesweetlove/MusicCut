package com.musiccut.core.dsp

import com.musiccut.core.audio.AudioBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** ITU-R BS.1770-4 / EBU R128 loudness results. */
data class LoudnessResult(
    val integratedLufs: Float,
    val shortTermMaxLufs: Float,
    val loudnessRange: Float,
    val truePeakDb: Float,
)

/**
 * K-weighted loudness meter (momentary 400 ms, short-term 3 s, gated integrated loudness, LRA and
 * 4x-oversampled true peak). Usable offline ([measure]) or streaming ([process] + readouts).
 */
class LoudnessMeter(private val sampleRate: Int) {
    private val shelf = Biquad().apply { set(FilterType.HIGH_SHELF, sampleRate, 1681.97f, 0.7072f, 3.9998f) }
    private val highPass = Biquad().apply { set(FilterType.HIGH_PASS, sampleRate, 38.135f, 0.5003f) }
    private val blockSize = sampleRate / 10 // 100 ms sub-blocks
    private var acc = 0.0
    private var accN = 0
    private val subBlocks = ArrayDeque<Double>() // mean-square per 100 ms
    private val momentaryBlocks = ArrayList<Double>() // 400 ms blocks (75 % overlap) for gating
    private val shortTermValues = ArrayList<Double>()
    private var peak = 0f
    private val prevL = FloatArray(3)
    private val prevR = FloatArray(3)

    @Volatile var momentaryLufs = -70f
        private set
    @Volatile var shortTermLufs = -70f
        private set
    @Volatile var truePeakDb = -70f
        private set

    fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (i in offset until offset + frames) {
            val l = left[i]; val r = right[i]
            truePeak(l, prevL); truePeak(r, prevR)
            val kl = highPass.processL(shelf.processL(l))
            val kr = highPass.processR(shelf.processR(r))
            acc += (kl * kl + kr * kr).toDouble()
            if (++accN == blockSize) {
                subBlocks.addLast(acc / blockSize)
                if (subBlocks.size > 30) subBlocks.removeFirst()
                acc = 0.0; accN = 0
                if (subBlocks.size >= 4) {
                    val m = subBlocks.takeLast(4).average()
                    momentaryBlocks += m
                    momentaryLufs = toLufs(m)
                }
                if (subBlocks.size >= 30) {
                    val s = subBlocks.average()
                    shortTermValues += s
                    shortTermLufs = toLufs(s)
                }
            }
        }
        truePeakDb = if (peak > 0) 20f * log10(peak) else -70f
    }

    private fun truePeak(x: Float, prev: FloatArray) {
        // 4x oversampling with cubic (Catmull-Rom) interpolation between the last samples
        val y0 = prev[0]; val y1 = prev[1]; val y2 = prev[2]; val y3 = x
        for (k in 1..3) {
            val t = k / 4f
            val v = 0.5f * ((2 * y1) + (-y0 + y2) * t + (2 * y0 - 5 * y1 + 4 * y2 - y3) * t * t + (-y0 + 3 * y1 - 3 * y2 + y3) * t * t * t)
            peak = max(peak, abs(v))
        }
        peak = max(peak, abs(x))
        prev[0] = y1; prev[1] = y2; prev[2] = y3
    }

    fun integratedLufs(): Float {
        val abs = momentaryBlocks.filter { toLufs(it) > -70f }
        if (abs.isEmpty()) return -70f
        val relGate = toLufs(abs.average()) - 10f
        val gated = abs.filter { toLufs(it) > relGate }
        return if (gated.isEmpty()) -70f else toLufs(gated.average())
    }

    fun loudnessRange(): Float {
        val abs = shortTermValues.filter { toLufs(it) > -70f }
        if (abs.size < 2) return 0f
        val relGate = toLufs(abs.average()) - 20f
        val vals = abs.map { toLufs(it) }.filter { it > relGate }.sorted()
        if (vals.size < 2) return 0f
        val lo = vals[(vals.size * 0.10).toInt().coerceAtMost(vals.size - 1)]
        val hi = vals[(vals.size * 0.95).toInt().coerceAtMost(vals.size - 1)]
        return hi - lo
    }

    fun result() = LoudnessResult(
        integratedLufs(),
        shortTermValues.maxOfOrNull { toLufs(it) } ?: -70f,
        loudnessRange(),
        truePeakDb,
    )

    fun reset() {
        shelf.reset(); highPass.reset(); acc = 0.0; accN = 0
        subBlocks.clear(); momentaryBlocks.clear(); shortTermValues.clear()
        peak = 0f; prevL.fill(0f); prevR.fill(0f)
        momentaryLufs = -70f; shortTermLufs = -70f; truePeakDb = -70f
    }

    companion object {
        fun toLufs(meanSquare: Double): Float = if (meanSquare <= 1e-12) -70f else (-0.691 + 10 * log10(meanSquare)).toFloat()

        fun measure(buffer: AudioBuffer): LoudnessResult {
            val m = LoudnessMeter(buffer.sampleRate)
            var pos = 0
            val l = buffer.left; val r = buffer.right
            while (pos < buffer.frames) {
                val n = minOf(8192, buffer.frames - pos)
                m.process(l, r, pos, n)
                pos += n
            }
            return m.result()
        }

        /** Gain (dB) that brings [buffer] to [targetLufs] without exceeding [ceilingDbtp] true peak. */
        fun normalizationGain(result: LoudnessResult, targetLufs: Float, ceilingDbtp: Float = -1f): Float {
            if (result.integratedLufs <= -70f) return 0f
            val g = targetLufs - result.integratedLufs
            return minOf(g, ceilingDbtp - result.truePeakDb)
        }

        fun dbToLinear(db: Float) = 10f.pow(db / 20f)
    }
}
