package com.musiccut.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType { LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH, PEAK, LOW_SHELF, HIGH_SHELF, ALL_PASS }

/** RBJ cookbook biquad (transposed direct form II), stereo state. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var zL1 = 0f; private var zL2 = 0f
    private var zR1 = 0f; private var zR2 = 0f

    fun set(type: FilterType, sampleRate: Int, freq: Float, q: Float = 0.707f, gainDb: Float = 0f) {
        val f = freq.coerceIn(10f, sampleRate * 0.49f)
        val w0 = 2.0 * PI * f / sampleRate
        val cw = cos(w0)
        val sw = sin(w0)
        val qq = q.coerceAtLeast(0.05f).toDouble()
        val alpha = sw / (2.0 * qq)
        val a = 10.0.pow(gainDb / 40.0)
        var nb0: Double; var nb1: Double; var nb2: Double
        var na0: Double; var na1: Double; var na2: Double
        when (type) {
            FilterType.LOW_PASS -> {
                nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2
                na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha
            }
            FilterType.HIGH_PASS -> {
                nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2
                na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha
            }
            FilterType.BAND_PASS -> {
                nb0 = alpha; nb1 = 0.0; nb2 = -alpha
                na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha
            }
            FilterType.NOTCH -> {
                nb0 = 1.0; nb1 = -2 * cw; nb2 = 1.0
                na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha
            }
            FilterType.ALL_PASS -> {
                nb0 = 1 - alpha; nb1 = -2 * cw; nb2 = 1 + alpha
                na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha
            }
            FilterType.PEAK -> {
                nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a
                na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) - (a - 1) * cw + s)
                nb1 = 2 * a * ((a - 1) - (a + 1) * cw)
                nb2 = a * ((a + 1) - (a - 1) * cw - s)
                na0 = (a + 1) + (a - 1) * cw + s
                na1 = -2 * ((a - 1) + (a + 1) * cw)
                na2 = (a + 1) + (a - 1) * cw - s
            }
            FilterType.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) + (a - 1) * cw + s)
                nb1 = -2 * a * ((a - 1) + (a + 1) * cw)
                nb2 = a * ((a + 1) + (a - 1) * cw - s)
                na0 = (a + 1) - (a - 1) * cw + s
                na1 = 2 * ((a - 1) - (a + 1) * cw)
                na2 = (a + 1) - (a - 1) * cw - s
            }
        }
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }

    fun processL(x: Float): Float {
        val y = b0 * x + zL1
        zL1 = sanitize(b1 * x - a1 * y + zL2)
        zL2 = sanitize(b2 * x - a2 * y)
        return y
    }

    fun processR(x: Float): Float {
        val y = b0 * x + zR1
        zR1 = sanitize(b1 * x - a1 * y + zR2)
        zR2 = sanitize(b2 * x - a2 * y)
        return y
    }

    fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (i in offset until offset + frames) {
            left[i] = processL(left[i])
            right[i] = processR(right[i])
        }
    }

    fun reset() {
        zL1 = 0f; zL2 = 0f; zR1 = 0f; zR2 = 0f
    }
}

/**
 * Topology-preserving-transform state-variable filter (Zavalishin). Stable under fast modulation,
 * which makes it the right choice for auto-filters.
 */
class StateVariableFilter {
    private var g = 0f; private var k = 1f
    private var a1 = 0f; private var a2 = 0f; private var a3 = 0f
    private val ic1 = FloatArray(2)
    private val ic2 = FloatArray(2)

    fun set(sampleRate: Int, freq: Float, resonance: Float) {
        val f = freq.coerceIn(20f, sampleRate * 0.45f)
        g = kotlin.math.tan(PI * f / sampleRate).toFloat()
        k = 2f - 2f * resonance.coerceIn(0f, 0.98f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    /** mode: 0 = LP, 1 = BP, 2 = HP */
    fun process(ch: Int, x: Float, mode: Int): Float {
        val v3 = x - ic2[ch]
        val v1 = a1 * ic1[ch] + a2 * v3
        val v2 = ic2[ch] + a2 * ic1[ch] + a3 * v3
        ic1[ch] = sanitize(2f * v1 - ic1[ch])
        ic2[ch] = sanitize(2f * v2 - ic2[ch])
        return when (mode) {
            0 -> v2
            1 -> v1
            else -> x - k * v1 - v2
        }
    }

    fun reset() { ic1.fill(0f); ic2.fill(0f) }
}
