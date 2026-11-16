package com.musiccut.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex DFT of any length (Bluestein's chirp-z algorithm on top of the radix-2 [Fft]).
 * Needed for model-defined sizes such as the 7680-point STFT of MDX-Net.
 */
class Dft(val size: Int) {
    private val pow2 = size and (size - 1) == 0
    private val direct = if (pow2) Fft(size) else null
    private val m = if (pow2) size else Integer.highestOneBit(2 * size - 1) * 2
    private val fft = if (pow2) null else Fft(m)
    private val wRe = FloatArray(size)
    private val wIm = FloatArray(size)
    private val bRe = FloatArray(m)
    private val bIm = FloatArray(m)
    private val aRe = FloatArray(m)
    private val aIm = FloatArray(m)

    init {
        if (!pow2) {
            for (k in 0 until size) {
                // chirp exp(-i*pi*k^2/n); k^2 mod 2n keeps precision for large k
                val kk = (k.toLong() * k) % (2L * size)
                val ang = PI * kk / size
                wRe[k] = cos(ang).toFloat()
                wIm[k] = -sin(ang).toFloat()
            }
            bRe[0] = wRe[0]; bIm[0] = -wIm[0]
            for (k in 1 until size) {
                bRe[k] = wRe[k]; bIm[k] = -wIm[k]
                bRe[m - k] = wRe[k]; bIm[m - k] = -wIm[k]
            }
            fft!!.forward(bRe, bIm)
        }
    }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, false)

    /** Inverse including 1/N scaling. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, true)
        val s = 1f / size
        for (i in 0 until size) { re[i] *= s; im[i] *= s }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        if (direct != null) {
            if (inverse) {
                // inverse via conjugation, without the scale (applied by caller)
                for (i in 0 until size) im[i] = -im[i]
                direct.forward(re, im)
                for (i in 0 until size) im[i] = -im[i]
            } else direct.forward(re, im)
            return
        }
        val f = fft!!
        java.util.Arrays.fill(aRe, 0f)
        java.util.Arrays.fill(aIm, 0f)
        for (k in 0 until size) {
            val xr = re[k]
            val xi = if (inverse) -im[k] else im[k]
            aRe[k] = xr * wRe[k] - xi * wIm[k]
            aIm[k] = xr * wIm[k] + xi * wRe[k]
        }
        f.forward(aRe, aIm)
        for (i in 0 until m) {
            val r = aRe[i] * bRe[i] - aIm[i] * bIm[i]
            val ii = aRe[i] * bIm[i] + aIm[i] * bRe[i]
            aRe[i] = r; aIm[i] = ii
        }
        f.inverse(aRe, aIm)
        for (k in 0 until size) {
            val r = aRe[k] * wRe[k] - aIm[k] * wIm[k]
            val ii = aRe[k] * wIm[k] + aIm[k] * wRe[k]
            re[k] = r
            im[k] = if (inverse) -ii else ii
        }
    }
}
