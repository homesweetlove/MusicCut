package com.musiccut.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Offline windowed-sinc resampler (Blackman window). The kernel is tabulated once per call and
 * linearly interpolated, so a full song converts in a few seconds on a phone.
 */
object Resampler {
    private const val HALF_TAPS = 16
    private const val TABLE_RES = 512

    fun resample(buffer: AudioBuffer, targetRate: Int, progress: ((Float) -> Unit)? = null): AudioBuffer {
        if (buffer.sampleRate == targetRate || buffer.frames == 0) {
            return AudioBuffer(buffer.left, buffer.right, targetRate)
        }
        val ratio = targetRate.toDouble() / buffer.sampleRate
        val outFrames = floor(buffer.frames * ratio).toInt()
        val cutoff = min(1.0, ratio) * 0.95
        val taps = (HALF_TAPS / min(1.0, ratio)).toInt()
        val table = buildTable(cutoff, taps)
        val src = buffer.frames
        val l = FloatArray(outFrames)
        val r = FloatArray(outFrames)
        val inL = buffer.left
        val inR = buffer.right
        for (i in 0 until outFrames) {
            val srcPos = i / ratio
            val center = floor(srcPos).toInt()
            val frac = srcPos - center
            var accL = 0f
            var accR = 0f
            var norm = 0f
            val kStart = maxOf(0, center - taps + 1)
            val kEnd = min(src - 1, center + taps)
            for (k in kStart..kEnd) {
                // distance in taps, always within [-taps, taps]
                val x = (k - center) - frac
                val t = (x + taps) * TABLE_RES
                val ti = t.toInt()
                val tf = (t - ti).toFloat()
                val w = if (ti + 1 < table.size) table[ti] + (table[ti + 1] - table[ti]) * tf else 0f
                accL += inL[k] * w
                accR += inR[k] * w
                norm += w
            }
            if (norm != 0f) {
                accL /= norm
                accR /= norm
            }
            l[i] = accL
            r[i] = accR
            if (progress != null && i and 0xFFFF == 0) progress(i.toFloat() / outFrames)
        }
        return AudioBuffer(l, r, targetRate)
    }

    private fun buildTable(cutoff: Double, taps: Int): FloatArray {
        val size = 2 * taps * TABLE_RES + 1
        return FloatArray(size) { idx ->
            val x = idx.toDouble() / TABLE_RES - taps
            val sinc = if (x == 0.0) cutoff else sin(PI * x * cutoff) / (PI * x)
            val n = (x + taps) / (2.0 * taps)
            val window = 0.42 - 0.5 * cos(2 * PI * n) + 0.08 * cos(4 * PI * n)
            (sinc * window).toFloat()
        }
    }
}
