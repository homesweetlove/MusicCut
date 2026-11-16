package com.musiccut.core.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

fun dbToGain(db: Float): Float = if (db <= -120f) 0f else 10f.pow(db / 20f)
fun gainToDb(g: Float): Float = 20f * log10(max(g, 1e-6f))

/** One-pole smoothing coefficient for a time constant in milliseconds. */
fun timeCoef(ms: Float, sampleRate: Int): Float =
    if (ms <= 0f) 0f else exp(-1.0 / (ms * 0.001 * sampleRate)).toFloat()

/** Flush denormals / NaNs that would otherwise poison recursive filters. */
fun sanitize(x: Float): Float = if (x.isNaN() || x.isInfinite()) 0f else if (x > -1e-20f && x < 1e-20f) 0f else x

/** Linear-interpolated per-parameter smoother to avoid zipper noise. */
class Smoother(initial: Float = 0f) {
    var current = initial
        private set
    fun next(target: Float, coef: Float = 0.999f): Float {
        current = target + (current - target) * coef
        return current
    }
    fun snap(v: Float) { current = v }
}
