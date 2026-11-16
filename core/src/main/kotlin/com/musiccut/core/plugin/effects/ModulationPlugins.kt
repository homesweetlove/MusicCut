package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.DelayLine
import com.musiccut.core.dsp.Lfo
import com.musiccut.core.dsp.sanitize
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.ParamScale
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.mixWet
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tan

private const val TWO_PI = 2.0 * PI

/** Logic "Chorus" / "Ensemble" lite: two modulated voices per side. */
class Chorus(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val dl = DelayLine((sr * 0.06f).toInt())
    private val dr = DelayLine((sr * 0.06f).toInt())
    private val lfo = Lfo(sr)

    override fun update() { lfo.rateHz = param("rate") }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val base = param("delay") * 0.001f * sampleRate
        val depth = param("depth") / 100f * 0.006f * sampleRate
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val p = lfo.next()
            val m1 = sin(TWO_PI * p).toFloat()
            val m2 = sin(TWO_PI * (p + 0.25)).toFloat()
            dl.push(left[i]); dr.push(right[i])
            val wl = 0.5f * (dl.read(base + depth * m1) + dl.read(base + depth * 0.7f * m2))
            val wr = 0.5f * (dr.read(base - depth * m1 + depth) + dr.read(base + depth * 0.7f * -m2 + depth))
            left[i] = mixWet(left[i], wl, mix)
            right[i] = mixWet(right[i], wr, mix)
        }
    }

    override fun reset() { dl.clear(); dr.clear() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "chorus", name = "Chorus", category = PluginCategory.MODULATION,
            description = "Lush stereo chorus",
            params = listOf(
                ParamSpec("rate", "Rate", 0.05f, 8f, 0.6f, "Hz", ParamScale.LOG),
                ParamSpec("depth", "Intensity", 0f, 100f, 45f, "%"),
                ParamSpec("delay", "Delay", 5f, 30f, 14f, "ms"),
                ParamSpec("mix", "Mix", 0f, 100f, 50f, "%"),
            ),
            factory = ::Chorus,
        )
    }
}

/** Logic "Flanger". */
class Flanger(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val dl = DelayLine((sr * 0.03f).toInt())
    private val dr = DelayLine((sr * 0.03f).toInt())
    private val lfo = Lfo(sr)
    private var fbL = 0f; private var fbR = 0f

    override fun update() { lfo.rateHz = param("rate") }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val base = param("delay") * 0.001f * sampleRate
        val depth = param("depth") / 100f * base
        val fb = param("feedback") / 100f
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val p = lfo.next()
            val ml = sin(TWO_PI * p).toFloat()
            val mr = sin(TWO_PI * (p + 0.25)).toFloat()
            dl.push(left[i] + fbL * fb); dr.push(right[i] + fbR * fb)
            val wl = dl.read((base + depth * ml).coerceAtLeast(1f))
            val wr = dr.read((base + depth * mr).coerceAtLeast(1f))
            fbL = sanitize(wl); fbR = sanitize(wr)
            left[i] = mixWet(left[i], wl, mix)
            right[i] = mixWet(right[i], wr, mix)
        }
    }

    override fun reset() { dl.clear(); dr.clear(); fbL = 0f; fbR = 0f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "flanger", name = "Flanger", category = PluginCategory.MODULATION,
            description = "Jet-plane sweep",
            params = listOf(
                ParamSpec("rate", "Rate", 0.02f, 5f, 0.25f, "Hz", ParamScale.LOG),
                ParamSpec("depth", "Intensity", 0f, 100f, 70f, "%"),
                ParamSpec("delay", "Delay", 0.5f, 10f, 3f, "ms"),
                ParamSpec("feedback", "Feedback", -95f, 95f, 50f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 50f, "%"),
            ),
            factory = ::Flanger,
        )
    }
}

/** Logic "Phaser": cascaded first-order all-pass stages swept by an LFO. */
class Phaser(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val zl = FloatArray(12)
    private val zr = FloatArray(12)
    private val lfo = Lfo(sr)
    private var fbL = 0f; private var fbR = 0f
    private var stages = 4

    override fun update() {
        lfo.rateHz = param("rate")
        stages = intArrayOf(4, 6, 8, 12)[param("stages").toInt().coerceIn(0, 3)]
    }

    private fun coef(freq: Double): Float {
        val t = tan(PI * freq / sampleRate)
        return ((t - 1) / (t + 1)).toFloat()
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val lo = param("low").toDouble(); val hi = param("high").toDouble()
        val fb = param("feedback") / 100f
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val p = lfo.next()
            val ml = 0.5 + 0.5 * sin(TWO_PI * p)
            val mr = 0.5 + 0.5 * sin(TWO_PI * (p + 0.25))
            val cl = coef(lo * Math.pow(hi / lo, ml))
            val cr = coef(lo * Math.pow(hi / lo, mr))
            var xl = left[i] + fbL * fb
            var xr = right[i] + fbR * fb
            for (s in 0 until stages) {
                val yl = cl * xl + zl[s]; zl[s] = sanitize(xl - cl * yl); xl = yl
                val yr = cr * xr + zr[s]; zr[s] = sanitize(xr - cr * yr); xr = yr
            }
            fbL = sanitize(xl); fbR = sanitize(xr)
            left[i] = mixWet(left[i], xl, mix)
            right[i] = mixWet(right[i], xr, mix)
        }
    }

    override fun reset() { zl.fill(0f); zr.fill(0f); fbL = 0f; fbR = 0f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "phaser", name = "Phaser", category = PluginCategory.MODULATION,
            description = "Classic multi-stage phaser",
            params = listOf(
                ParamSpec("rate", "Rate", 0.02f, 8f, 0.4f, "Hz", ParamScale.LOG),
                ParamSpec("stages", "Stages", 0f, 3f, 1f, choices = listOf("4", "6", "8", "12")),
                ParamSpec("low", "Sweep Low", 50f, 2000f, 200f, "Hz", ParamScale.LOG),
                ParamSpec("high", "Sweep High", 1000f, 16000f, 4000f, "Hz", ParamScale.LOG),
                ParamSpec("feedback", "Feedback", -90f, 90f, 40f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 50f, "%"),
            ),
            factory = ::Phaser,
        )
    }
}

/** Logic "Tremolo" (with stereo phase for auto-pan). */
class Tremolo(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val lfo = Lfo(sr)

    override fun update() { lfo.rateHz = param("rate") }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val depth = param("depth") / 100f
        val phase = param("phase") / 360.0
        val shape = param("shape").toInt()
        for (i in offset until offset + frames) {
            val p = lfo.next()
            left[i] *= 1f - depth * wave(p, shape)
            right[i] *= 1f - depth * wave(p + phase, shape)
        }
    }

    private fun wave(p: Double, shape: Int): Float {
        val s = sin(TWO_PI * p)
        return when (shape) {
            1 -> if (s >= 0) 1f else 0f
            else -> (0.5 + 0.5 * s).toFloat()
        }
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "tremolo", name = "Tremolo", category = PluginCategory.MODULATION,
            description = "Amplitude modulation / auto-pan",
            params = listOf(
                ParamSpec("rate", "Rate", 0.1f, 20f, 4f, "Hz", ParamScale.LOG),
                ParamSpec("depth", "Depth", 0f, 100f, 50f, "%"),
                ParamSpec("phase", "Stereo Phase", 0f, 180f, 0f, "°"),
                ParamSpec("shape", "Shape", 0f, 1f, 0f, choices = listOf("Sine", "Square")),
            ),
            presets = mapOf("Auto Pan" to mapOf("rate" to 1f, "depth" to 80f, "phase" to 180f)),
            factory = ::Tremolo,
        )
    }
}
