package com.musiccut.core.dsp

import com.musiccut.core.audio.AudioBuffer
import kotlin.math.sqrt

data class TempoEstimate(val bpm: Float, val firstBeatFrame: Long, val confidence: Float)

/** Tempo / beat detection from a spectral-flux onset envelope and autocorrelation. */
object TempoDetector {
    fun detect(buffer: AudioBuffer, minBpm: Float = 70f, maxBpm: Float = 180f): TempoEstimate {
        val n = 1024
        val hop = 512
        val fft = Fft(n)
        val window = FloatArray(n) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / n)).toFloat() }
        val prev = FloatArray(n / 2)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val frames = (buffer.frames - n) / hop
        if (frames < 16) return TempoEstimate(120f, 0, 0f)
        val flux = FloatArray(frames)
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until n) {
                re[i] = (buffer.left[off + i] + buffer.right[off + i]) * 0.5f * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            var s = 0f
            for (k in 1 until n / 2) {
                val m = kotlin.math.ln(1f + 100f * sqrt(re[k] * re[k] + im[k] * im[k]))
                val d = m - prev[k]
                if (d > 0) s += d
                prev[k] = m
            }
            flux[f] = s
        }
        // remove local mean
        val env = FloatArray(frames)
        for (i in 0 until frames) {
            var mean = 0f
            var c = 0
            for (j in maxOf(0, i - 8)..minOf(frames - 1, i + 8)) { mean += flux[j]; c++ }
            env[i] = maxOf(0f, flux[i] - mean / c)
        }
        val fps = buffer.sampleRate.toFloat() / hop
        val minLag = (60f / maxBpm * fps).toInt()
        val maxLag = (60f / minBpm * fps).toInt()
        var bestLag = minLag
        var best = -1f
        var total = 0f
        val scores = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var s = 0f
            for (i in 0 until frames - lag) s += env[i] * env[i + lag]
            // prefer tempi near 120 slightly (perceptual weighting)
            val bpm = 60f * fps / lag
            val w = kotlin.math.exp(-0.5f * (kotlin.math.ln(bpm / 120f) / 0.7f).let { it * it })
            scores[lag] = s * w
            total += scores[lag]
            if (scores[lag] > best) { best = scores[lag]; bestLag = lag }
        }
        // refine with parabolic interpolation
        val a = scores.getOrElse(bestLag - 1) { best }
        val c = scores.getOrElse(bestLag + 1) { best }
        val denom = a - 2 * best + c
        val lag = bestLag + if (denom != 0f) 0.5f * (a - c) / denom else 0f
        val bpm = 60f * fps / lag
        // beat phase: offset maximizing onset energy on the beat grid
        var bestPhase = 0
        var bestSum = -1f
        for (ph in 0 until bestLag) {
            var s = 0f
            var i = ph.toFloat()
            while (i < frames) { s += env[i.toInt()]; i += lag }
            if (s > bestSum) { bestSum = s; bestPhase = ph }
        }
        val confidence = if (total > 0) (best / (total / (maxLag - minLag + 1))) / 10f else 0f
        return TempoEstimate(bpm, bestPhase.toLong() * hop + n / 2, confidence.coerceIn(0f, 1f))
    }
}
