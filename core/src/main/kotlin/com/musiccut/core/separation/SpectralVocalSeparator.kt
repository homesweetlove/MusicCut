package com.musiccut.core.separation

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.dsp.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Center-channel vocal extraction in the STFT domain.
 *
 * For every time/frequency bin the left/right spectra are compared: lead vocals in commercial mixes
 * are panned center and in phase, so bins with high inter-channel coherence and balanced level form
 * a soft mask. The mask is band-limited to the vocal range, de-weighted on drum-like transients and
 * smoothed over time to avoid "musical noise". Vocals = mask·X, instrumental = X − vocals, so the
 * two stems always sum back to the original exactly.
 *
 * Both channels are transformed with one complex FFT (L + iR packing).
 */
class SpectralVocalSeparator : StemSeparator {
    override val name = "Spectral Center Extraction"

    override fun separate(
        input: AudioBuffer,
        options: SeparationOptions,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Stems {
        val n = options.quality.fftSize
        val hop = n / 4
        val half = n / 2
        val fft = Fft(n)
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val olaNorm = 1f / 1.5f // Hann² at 75 % overlap sums to 1.5

        val frames = input.frames
        val sr = input.sampleRate
        val vocL = FloatArray(frames)
        val vocR = FloatArray(frames)

        val re = FloatArray(n)
        val im = FloatArray(n)
        val smoothMask = FloatArray(half + 1)
        val avgMag = FloatArray(half + 1)
        val bandWeight = FloatArray(half + 1) { k -> band(k.toFloat() * sr / n, options.vocalLowHz, options.vocalHighHz) }
        val coherencePow = 1f + options.strength * 5f
        val balancePow = 0.5f + options.strength * 2f
        val perc = options.percussionSuppression.coerceIn(0f, 1f)

        var pos = -n + hop
        val totalSteps = ((frames + n) / hop).coerceAtLeast(1)
        var step = 0
        while (pos < frames) {
            if (isCancelled()) throw InterruptedException("cancelled")
            for (i in 0 until n) {
                val idx = pos + i
                if (idx in 0 until frames) {
                    re[i] = input.left[idx] * window[i]
                    im[i] = input.right[idx] * window[i]
                } else {
                    re[i] = 0f; im[i] = 0f
                }
            }
            fft.forward(re, im)

            for (k in 0..half) {
                val nk = if (k == 0) 0 else n - k
                val lr = (re[k] + re[nk]) * 0.5f
                val li = (im[k] - im[nk]) * 0.5f
                val rr = (im[k] + im[nk]) * 0.5f
                val ri = -(re[k] - re[nk]) * 0.5f

                val pl = lr * lr + li * li
                val pr = rr * rr + ri * ri
                val cross = lr * rr + li * ri
                val energy = pl + pr + 1e-12f
                val coherence = (2f * cross / energy).coerceIn(0f, 1f)
                val ml = sqrt(pl); val mr = sqrt(pr)
                val balance = min(ml, mr) / (max(ml, mr) + 1e-9f)
                var mask = coherence.pow(coherencePow) * balance.pow(balancePow) * bandWeight[k]

                val mag = ml + mr
                if (perc > 0f) {
                    val ratio = mag / (avgMag[k] + 1e-9f)
                    if (ratio > 2.5f) mask *= 1f - perc * min(1f, (ratio - 2.5f) / 4f)
                }
                avgMag[k] = avgMag[k] * 0.92f + mag * 0.08f

                // asymmetric temporal smoothing: quick to open, slower to close
                val prev = smoothMask[k]
                val m = if (mask > prev) prev + (mask - prev) * 0.7f else prev + (mask - prev) * 0.35f
                smoothMask[k] = m

                val vlr = lr * m; val vli = li * m
                val vrr = rr * m; val vri = ri * m
                if (k == 0 || k == half) {
                    re[k] = vlr
                    im[k] = vrr
                } else {
                    re[k] = vlr - vri; im[k] = vli + vrr
                    re[nk] = vlr + vri; im[nk] = -vli + vrr
                }
            }

            fft.inverse(re, im)
            for (i in 0 until n) {
                val idx = pos + i
                if (idx in 0 until frames) {
                    val w = window[i] * olaNorm
                    vocL[idx] += re[i] * w
                    vocR[idx] += im[i] * w
                }
            }
            pos += hop
            step++
            if (step and 15 == 0) progress(step.toFloat() / totalSteps)
        }

        val instL = FloatArray(frames) { input.left[it] - vocL[it] }
        val instR = FloatArray(frames) { input.right[it] - vocR[it] }
        progress(1f)
        return Stems(AudioBuffer(vocL, vocR, sr), AudioBuffer(instL, instR, sr))
    }

    private fun band(f: Float, lo: Float, hi: Float): Float {
        val low = smooth((f - lo * 0.6f) / (lo * 0.4f + 1f))
        val high = 1f - smooth((f - hi) / (hi * 0.4f))
        return low * high
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
