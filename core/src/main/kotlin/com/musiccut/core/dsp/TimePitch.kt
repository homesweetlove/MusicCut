package com.musiccut.core.dsp

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.Resampler
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline high-quality time stretching and pitch shifting ("Flex Time" / "Flex Pitch" engine):
 * a phase vocoder with identity phase locking (Laroche & Dolson), processed per channel.
 */
object TimePitch {
    private const val N = 4096
    private const val HOP_OUT = N / 4

    /** Changes duration by [ratio] (2.0 = twice as long) without changing pitch. */
    fun stretch(input: AudioBuffer, ratio: Double, progress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): AudioBuffer {
        if (kotlin.math.abs(ratio - 1.0) < 1e-4) return AudioBuffer(input.left.copyOf(), input.right.copyOf(), input.sampleRate)
        val l = stretchChannel(input.left, ratio, { progress(it * 0.5f) }, isCancelled)
        val r = stretchChannel(input.right, ratio, { progress(0.5f + it * 0.5f) }, isCancelled)
        val n = minOf(l.size, r.size)
        return AudioBuffer(l.copyOf(n), r.copyOf(n), input.sampleRate)
    }

    /** Shifts pitch by [semitones] keeping the duration (stretch, then resample). */
    fun pitchShift(input: AudioBuffer, semitones: Double, progress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): AudioBuffer {
        if (kotlin.math.abs(semitones) < 1e-4) return AudioBuffer(input.left.copyOf(), input.right.copyOf(), input.sampleRate)
        val factor = 2.0.pow(semitones / 12.0)
        val stretched = stretch(input, factor, { progress(it * 0.85f) }, isCancelled)
        // play the stretched signal faster by `factor`: resample to sr/factor and relabel
        val targetRate = (input.sampleRate / factor).roundToInt()
        val res = Resampler.resample(AudioBuffer(stretched.left, stretched.right, input.sampleRate), targetRate)
        progress(1f)
        val n = minOf(res.frames, input.frames)
        return AudioBuffer(res.left.copyOf(n), res.right.copyOf(n), input.sampleRate)
    }

    /** Varispeed: speed and pitch change together (tape style). */
    fun varispeed(input: AudioBuffer, speed: Double): AudioBuffer {
        val targetRate = (input.sampleRate / speed).roundToInt()
        val res = Resampler.resample(input, targetRate)
        return AudioBuffer(res.left, res.right, input.sampleRate)
    }

    private fun stretchChannel(x: FloatArray, ratio: Double, progress: (Float) -> Unit, isCancelled: () -> Boolean): FloatArray {
        val hopIn = HOP_OUT / ratio
        val fft = Fft(N)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
        val outLen = (x.size * ratio).toInt() + N
        val out = FloatArray(outLen + N)
        val norm = FloatArray(outLen + N)
        val half = N / 2
        val lastPhase = FloatArray(half + 1)
        val sumPhase = FloatArray(half + 1)
        val mag = FloatArray(half + 1)
        val phase = FloatArray(half + 1)
        val re = FloatArray(N)
        val im = FloatArray(N)
        val expected = FloatArray(half + 1) { (2 * PI * it * hopIn / N).toFloat() }
        val outAdvance = FloatArray(half + 1) { (2 * PI * it * HOP_OUT / N).toFloat() }
        val frames = ((x.size) / hopIn).toInt() + 2
        var first = true
        for (f in 0 until frames) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val inPos = (f * hopIn).roundToInt() - half
            for (i in 0 until N) {
                val idx = inPos + i
                re[i] = if (idx in x.indices) x[idx] * window[i] else 0f
                im[i] = 0f
            }
            fft.forward(re, im)
            for (k in 0..half) {
                mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
                phase[k] = atan2(im[k], re[k])
            }
            if (first) {
                for (k in 0..half) sumPhase[k] = phase[k]
                first = false
            } else {
                // phase advance per bin, then identity phase locking around spectral peaks
                val adv = FloatArray(half + 1)
                for (k in 0..half) {
                    var dp = phase[k] - lastPhase[k] - expected[k]
                    dp -= (2 * PI * kotlin.math.round(dp / (2 * PI))).toFloat()
                    adv[k] = (outAdvance[k] + dp * (HOP_OUT / hopIn.toFloat()))
                }
                var k = 0
                while (k <= half) {
                    // find region around local peak
                    val isPeak = (k == 0 || mag[k] >= mag[k - 1]) && (k == half || mag[k] >= mag[k + 1])
                    if (isPeak) {
                        val peakPhase = sumPhase[k] + adv[k]
                        var lo = k
                        while (lo > 0 && mag[lo - 1] < mag[lo]) lo--
                        var hi = k
                        while (hi < half && mag[hi + 1] < mag[hi]) hi++
                        for (j in lo..hi) sumPhase[j] = peakPhase + (phase[j] - phase[k])
                        k = hi + 1
                    } else {
                        sumPhase[k] += adv[k]
                        k++
                    }
                }
            }
            for (k in 0..half) lastPhase[k] = phase[k]
            for (k in 0..half) {
                re[k] = mag[k] * cos(sumPhase[k])
                im[k] = mag[k] * sin(sumPhase[k])
            }
            for (k in 1 until half) {
                re[N - k] = re[k]
                im[N - k] = -im[k]
            }
            im[0] = 0f; im[half] = 0f
            fft.inverse(re, im)
            val outPos = f * HOP_OUT
            for (i in 0 until N) {
                val o = outPos + i
                if (o < out.size) {
                    out[o] += re[i] * window[i]
                    norm[o] += window[i] * window[i]
                }
            }
            if (f and 31 == 0) progress(f.toFloat() / frames)
        }
        val result = FloatArray((x.size * ratio).toInt())
        for (i in result.indices) {
            val o = i + half
            result[i] = if (o < out.size && norm[o] > 1e-3f) out[o] / norm[o] else 0f
        }
        progress(1f)
        return result
    }
}
