package com.musiccut.core.dsp

import kotlin.math.abs
import kotlin.math.max

/** Peak envelope follower with independent attack / release (dB-agnostic, linear domain). */
class EnvelopeFollower(private val sampleRate: Int) {
    private var attackCoef = 0f
    private var releaseCoef = 0f
    var value = 0f
        private set

    fun set(attackMs: Float, releaseMs: Float) {
        attackCoef = timeCoef(attackMs, sampleRate)
        releaseCoef = timeCoef(releaseMs, sampleRate)
    }

    fun process(x: Float): Float {
        val inp = abs(x)
        val c = if (inp > value) attackCoef else releaseCoef
        value = sanitize(inp + (value - inp) * c)
        return value
    }

    fun processStereo(l: Float, r: Float): Float = process(max(abs(l), abs(r)))

    fun reset() { value = 0f }
}

/** Sine LFO with phase offset per channel. */
class Lfo(private val sampleRate: Int) {
    private var phase = 0.0
    var rateHz = 1f

    fun next(): Double {
        val p = phase
        phase += rateHz / sampleRate
        if (phase >= 1.0) phase -= 1.0
        return p
    }

    fun reset() { phase = 0.0 }
}
