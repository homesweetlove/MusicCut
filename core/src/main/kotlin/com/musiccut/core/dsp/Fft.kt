package com.musiccut.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT with precomputed twiddles. */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    private val cosT = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val rev = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, false)

    /** Inverse transform including the 1/N scale. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, true)
        val s = 1f / size
        for (i in 0 until size) {
            re[i] *= s
            im[i] *= s
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
