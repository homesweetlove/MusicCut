package com.musiccut.core.plugin

import com.musiccut.core.dsp.Fft
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

object PluginAnalysis {
    /**
     * Magnitude response (dB) of a linear plugin such as an EQ or filter, measured with an impulse.
     * Returns [points] values for log-spaced frequencies between 20 Hz and 20 kHz.
     */
    fun frequencyResponse(descriptor: PluginDescriptor, params: Map<String, Float>, sampleRate: Int = 44100, points: Int = 128): FloatArray {
        val n = 8192
        val plugin = descriptor.factory(sampleRate)
        plugin.setAll(params)
        val l = FloatArray(n).also { it[0] = 1f }
        val r = FloatArray(n)
        plugin.run(l, r, 0, n)
        val im = FloatArray(n)
        Fft(n).forward(l, im)
        return FloatArray(points) { i ->
            val f = frequencyAt(i, points)
            val bin = (f * n / sampleRate).toInt().coerceIn(1, n / 2 - 1)
            val mag = sqrt(l[bin] * l[bin] + im[bin] * im[bin])
            (20 * log10(mag.coerceAtLeast(1e-6f))).coerceIn(-48f, 48f)
        }
    }

    /**
     * Log-frequency magnitude spectrum (dBFS, -96..0) of [samples] (power-of-two length), for
     * real-time analyzers.
     */
    fun spectrum(samples: FloatArray, sampleRate: Int, points: Int = 128): FloatArray {
        val n = samples.size
        val re = FloatArray(n) { samples[it] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * it / n).toFloat()) }
        val im = FloatArray(n)
        Fft(n).forward(re, im)
        val norm = 4f / n
        return FloatArray(points) { i ->
            val f0 = frequencyAt(i, points)
            val f1 = frequencyAt(minOf(i + 1, points - 1), points)
            val b0 = (f0 * n / sampleRate).toInt().coerceIn(1, n / 2 - 1)
            val b1 = (f1 * n / sampleRate).toInt().coerceIn(b0, n / 2 - 1)
            var peak = 0f
            for (b in b0..b1) peak = maxOf(peak, sqrt(re[b] * re[b] + im[b] * im[b]) * norm)
            (20 * log10(peak.coerceAtLeast(1e-6f))).coerceIn(-96f, 0f)
        }
    }

    fun frequencyAt(i: Int, points: Int): Float =
        exp(ln(20f) + (ln(20000f) - ln(20f)) * i / (points - 1))
}

/** Long-term 1/3-octave band levels (dB) of a signal, used by Match EQ. */
object SpectrumMatch {
    fun thirdOctaveLevels(buffer: com.musiccut.core.audio.AudioBuffer, bands: FloatArray, maxSeconds: Int = 90): FloatArray {
        val n = 8192
        val fft = Fft(n)
        val window = FloatArray(n) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / n)).toFloat() }
        val power = DoubleArray(n / 2)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val limit = minOf(buffer.frames, buffer.sampleRate * maxSeconds)
        var frames = 0
        var pos = 0
        while (pos + n <= limit) {
            for (i in 0 until n) { re[i] = (buffer.left[pos + i] + buffer.right[pos + i]) * 0.5f * window[i]; im[i] = 0f }
            fft.forward(re, im)
            for (k in 0 until n / 2) power[k] += (re[k] * re[k] + im[k] * im[k]).toDouble()
            frames++
            pos += n / 2
        }
        val binHz = buffer.sampleRate.toDouble() / n
        return FloatArray(bands.size) { b ->
            val lo = bands[b] / 2.0.pow(1.0 / 6)
            val hi = bands[b] * 2.0.pow(1.0 / 6)
            var s = 0.0
            var c = 0
            var k = maxOf(1, (lo / binHz).toInt())
            while (k < n / 2 && k * binHz < hi) { s += power[k]; c++; k++ }
            if (c == 0 || frames == 0) -120f else (10 * log10(s / c / frames + 1e-20)).toFloat()
        }
    }

    /** Correction curve = template − current, mean-removed, lightly smoothed and clamped. */
    fun matchCurve(template: FloatArray, current: FloatArray, maxDb: Float = 12f): FloatArray {
        val diff = FloatArray(template.size) { template[it] - current[it] }
        val valid = diff.filter { it > -60f && it < 60f }
        val mean = if (valid.isEmpty()) 0f else valid.average().toFloat()
        val centered = FloatArray(diff.size) { (diff[it] - mean).coerceIn(-maxDb, maxDb) }
        return FloatArray(diff.size) { i ->
            val a = centered[maxOf(0, i - 1)]; val b = centered[i]; val c = centered[minOf(diff.size - 1, i + 1)]
            (a + 2 * b + c) / 4f
        }
    }
}
