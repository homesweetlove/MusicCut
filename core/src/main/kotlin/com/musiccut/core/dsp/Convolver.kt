package com.musiccut.core.dsp

/**
 * Uniformly partitioned FFT convolution (overlap-save), stereo in / stereo out with a stereo IR.
 * Block size [block]; latency = [block] samples.
 */
class Convolver(irLeft: FloatArray, irRight: FloatArray, val block: Int = 512) {
    private val n = block * 2
    private val fft = Fft(n)
    private val parts = maxOf(1, (maxOf(irLeft.size, irRight.size) + block - 1) / block)
    private val hRe = Array(2) { Array(parts) { FloatArray(n) } }
    private val hIm = Array(2) { Array(parts) { FloatArray(n) } }
    // frequency-domain delay line of input blocks (per input channel)
    private val xRe = Array(2) { Array(parts) { FloatArray(n) } }
    private val xIm = Array(2) { Array(parts) { FloatArray(n) } }
    private var head = 0
    private val inBuf = Array(2) { FloatArray(n) }
    private val outBuf = Array(2) { FloatArray(block) }
    private var fill = 0
    private val accRe = FloatArray(n)
    private val accIm = FloatArray(n)
    private val tmpRe = FloatArray(n)
    private val tmpIm = FloatArray(n)

    init {
        val irs = arrayOf(irLeft, irRight)
        for (c in 0..1) for (p in 0 until parts) {
            val ir = irs[c]
            for (i in 0 until block) {
                val idx = p * block + i
                hRe[c][p][i] = if (idx < ir.size) ir[idx] else 0f
            }
            fft.forward(hRe[c][p], hIm[c][p])
        }
    }

    /** Processes in place; call with any frame count. */
    fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (i in offset until offset + frames) {
            inBuf[0][block + fill] = left[i]
            inBuf[1][block + fill] = right[i]
            left[i] = outBuf[0][fill]
            right[i] = outBuf[1][fill]
            if (++fill == block) {
                fill = 0
                processBlock()
            }
        }
    }

    private fun processBlock() {
        head = (head + parts - 1) % parts
        for (c in 0..1) {
            val re = xRe[c][head]
            val im = xIm[c][head]
            System.arraycopy(inBuf[c], 0, re, 0, n)
            java.util.Arrays.fill(im, 0f)
            fft.forward(re, im)
            // slide input window
            System.arraycopy(inBuf[c], block, inBuf[c], 0, block)
        }
        for (c in 0..1) {
            java.util.Arrays.fill(accRe, 0f)
            java.util.Arrays.fill(accIm, 0f)
            // true-stereo-lite: left IR on the mono sum drives left output, right IR drives right output
            for (p in 0 until parts) {
                val xi = (head + p) % parts
                val aR = xRe[0][xi]; val aI = xIm[0][xi]
                val bR = xRe[1][xi]; val bI = xIm[1][xi]
                val hr = hRe[c][p]; val hi = hIm[c][p]
                for (k in 0 until n) {
                    val sr = (aR[k] + bR[k]) * 0.5f
                    val si = (aI[k] + bI[k]) * 0.5f
                    accRe[k] += sr * hr[k] - si * hi[k]
                    accIm[k] += sr * hi[k] + si * hr[k]
                }
            }
            System.arraycopy(accRe, 0, tmpRe, 0, n)
            System.arraycopy(accIm, 0, tmpIm, 0, n)
            fft.inverse(tmpRe, tmpIm)
            System.arraycopy(tmpRe, block, outBuf[c], 0, block)
        }
    }

    fun reset() {
        for (c in 0..1) {
            inBuf[c].fill(0f); outBuf[c].fill(0f)
            for (p in 0 until parts) { xRe[c][p].fill(0f); xIm[c][p].fill(0f) }
        }
        fill = 0
    }
}
