package com.musiccut.core.dsp

/** Circular delay line with fractional (linear / cubic) reads. */
class DelayLine(maxSamples: Int) {
    private val buf = FloatArray(Integer.highestOneBit((maxOf(4, maxSamples) + 4) * 2 - 1))
    private val mask = buf.size - 1
    private var write = 0

    val capacity: Int get() = buf.size - 4

    fun push(x: Float) {
        buf[write] = x
        write = (write + 1) and mask
    }

    /** Read [delay] samples behind the most recently pushed sample (delay >= 1 means the previous one). */
    fun read(delay: Float): Float {
        val d = delay.coerceIn(0f, capacity.toFloat())
        val pos = write - 1 - d
        val i = kotlin.math.floor(pos).toInt()
        val frac = pos - i
        val y0 = buf[(i - 1) and mask]
        val y1 = buf[i and mask]
        val y2 = buf[(i + 1) and mask]
        val y3 = buf[(i + 2) and mask]
        // 4-point Hermite
        val c0 = y1
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return ((c3 * frac + c2) * frac + c1) * frac + c0
    }

    fun readInt(delay: Int): Float = buf[(write - 1 - delay) and mask]

    fun clear() {
        buf.fill(0f)
        write = 0
    }
}
