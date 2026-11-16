package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.Biquad
import com.musiccut.core.dsp.DelayLine
import com.musiccut.core.dsp.EnvelopeFollower
import com.musiccut.core.dsp.FilterType
import com.musiccut.core.dsp.Lfo
import com.musiccut.core.dsp.StateVariableFilter
import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.ParamScale
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.mixWet
import kotlin.math.PI
import kotlin.math.cos

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Logic "AutoFilter": resonant SVF with LFO and envelope modulation. */
class AutoFilter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val svf = StateVariableFilter()
    private val lfo = Lfo(sr)
    private val env = EnvelopeFollower(sr).apply { set(5f, 200f) }
    private var counter = 0

    override fun update() { lfo.rateHz = param("lfoRate") }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val mode = param("mode").toInt()
        val cutoff = param("cutoff")
        val res = param("resonance") / 100f
        val lfoDepth = param("lfoDepth") / 100f
        val envAmt = param("envelope") / 100f
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val e = env.processStereo(left[i], right[i])
            val p = lfo.next()
            if (counter-- <= 0) {
                counter = 16
                val octaves = lfoDepth * 3f * sin(2 * PI * p).toFloat() + envAmt * 4f * min(1f, e * 4f)
                svf.set(sampleRate, cutoff * 2f.pow(octaves), res)
            }
            val l = left[i]; val r = right[i]
            left[i] = mixWet(l, svf.process(0, l, mode), mix)
            right[i] = mixWet(r, svf.process(1, r, mode), mix)
        }
    }

    override fun reset() { svf.reset(); env.reset() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "autofilter", name = "AutoFilter", category = PluginCategory.FILTER,
            description = "Resonant filter with LFO / envelope sweep",
            params = listOf(
                ParamSpec("mode", "Mode", 0f, 2f, 0f, choices = listOf("Low Pass", "Band Pass", "High Pass")),
                ParamSpec("cutoff", "Cutoff", 40f, 18000f, 1200f, "Hz", ParamScale.LOG),
                ParamSpec("resonance", "Resonance", 0f, 100f, 30f, "%"),
                ParamSpec("lfoRate", "LFO Rate", 0.05f, 20f, 1f, "Hz", ParamScale.LOG),
                ParamSpec("lfoDepth", "LFO Depth", 0f, 100f, 40f, "%"),
                ParamSpec("envelope", "Envelope", 0f, 100f, 0f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 100f, "%"),
            ),
            factory = ::AutoFilter,
        )
    }
}

/** Static high/low cut filter ("Channel EQ" is heavier; this is the quick cleanup tool). */
class CutFilter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val hp = arrayOf(Biquad(), Biquad())
    private val lp = arrayOf(Biquad(), Biquad())

    override fun update() {
        for (b in hp) b.set(FilterType.HIGH_PASS, sampleRate, param("low"), 0.707f)
        for (b in lp) b.set(FilterType.LOW_PASS, sampleRate, param("high"), 0.707f)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (b in hp) b.process(left, right, offset, frames)
        for (b in lp) b.process(left, right, offset, frames)
    }

    override fun reset() { hp.forEach { it.reset() }; lp.forEach { it.reset() } }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "cut_filter", name = "High/Low Cut", category = PluginCategory.FILTER,
            description = "Steep 24 dB/oct high-pass and low-pass",
            params = listOf(
                ParamSpec("low", "High-Pass", 20f, 2000f, 30f, "Hz", ParamScale.LOG),
                ParamSpec("high", "Low-Pass", 1000f, 20000f, 20000f, "Hz", ParamScale.LOG),
            ),
            factory = ::CutFilter,
        )
    }
}

/**
 * Logic "Pitch Shifter": dual rotating read heads over a delay line with sin² crossfades. Good for
 * vocal doubling / harmonies and moderate transposition in real time.
 */
class PitchShifter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val window = (sr * 0.06f)
    private val dl = DelayLine((window * 2).toInt() + 16)
    private val dr = DelayLine((window * 2).toInt() + 16)
    private var phase = 0f
    private var ratio = 1f

    override fun update() {
        ratio = 2f.pow((param("semitones") + param("cents") / 100f) / 12f)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val mix = param("mix")
        val inc = (1f - ratio) / window
        for (i in offset until offset + frames) {
            dl.push(left[i]); dr.push(right[i])
            phase += inc
            if (phase >= 1f) phase -= 1f
            if (phase < 0f) phase += 1f
            val p2 = (phase + 0.5f) % 1f
            val d1 = 2f + phase * window
            val d2 = 2f + p2 * window
            val g1 = sin(PI * phase).toFloat().let { it * it }
            val g2 = sin(PI * p2).toFloat().let { it * it }
            val wl = dl.read(d1) * g1 + dl.read(d2) * g2
            val wr = dr.read(d1) * g1 + dr.read(d2) * g2
            left[i] = mixWet(left[i], wl, mix)
            right[i] = mixWet(right[i], wr, mix)
        }
    }

    override fun reset() { dl.clear(); dr.clear(); phase = 0f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "pitch_shifter", name = "Pitch Shifter", category = PluginCategory.PITCH,
            description = "Real-time transposition / harmonizer",
            params = listOf(
                ParamSpec("semitones", "Semitones", -12f, 12f, 0f, "st"),
                ParamSpec("cents", "Cents", -100f, 100f, 0f, "ct"),
                ParamSpec("mix", "Mix", 0f, 100f, 100f, "%"),
            ),
            presets = mapOf(
                "Octave Up" to mapOf("semitones" to 12f),
                "Octave Down" to mapOf("semitones" to -12f),
                "Double (±8ct)" to mapOf("cents" to 8f, "mix" to 50f),
                "Fifth Harmony" to mapOf("semitones" to 7f, "mix" to 45f),
            ),
            factory = ::PitchShifter,
        )
    }
}

/** Logic "Direction Mixer": stereo width (M/S) and balance. */
class DirectionMixer(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val width = param("spread") / 100f
        val pan = param("direction") / 100f
        val theta = (pan + 1f) * PI.toFloat() / 4f
        val gl = cos(theta) * sqrt(2f)
        val gr = sin(theta) * sqrt(2f)
        for (i in offset until offset + frames) {
            val m = (left[i] + right[i]) * 0.5f
            val s = (left[i] - right[i]) * 0.5f * width
            left[i] = (m + s) * gl
            right[i] = (m - s) * gr
        }
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "direction_mixer", name = "Direction Mixer", category = PluginCategory.IMAGING,
            description = "Stereo width and direction",
            params = listOf(
                ParamSpec("spread", "Spread", 0f, 200f, 100f, "%"),
                ParamSpec("direction", "Direction", -100f, 100f, 0f, ""),
            ),
            factory = ::DirectionMixer,
        )
    }
}

/** Logic "Stereo Spread": widens mono sources by complementary delays above a crossover. */
class StereoSpread(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val hp = Biquad()
    private val dl = DelayLine((sr * 0.04f).toInt())
    private val dr = DelayLine((sr * 0.04f).toInt())

    override fun update() { hp.set(FilterType.HIGH_PASS, sampleRate, param("lowFreq"), 0.707f) }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val amt = param("intensity") / 100f
        val tl = param("time") * 0.001f * sampleRate
        val tr = tl * 1.37f
        for (i in offset until offset + frames) {
            val m = (left[i] + right[i]) * 0.5f
            val h = hp.processL(m)
            dl.push(h); dr.push(h)
            val side = (dl.read(tl) - dr.read(tr)) * 0.5f * amt
            left[i] += side
            right[i] -= side
        }
    }

    override fun reset() { hp.reset(); dl.clear(); dr.clear() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "stereo_spread", name = "Stereo Spread", category = PluginCategory.IMAGING,
            description = "Pseudo-stereo widening for mono material",
            params = listOf(
                ParamSpec("intensity", "Intensity", 0f, 100f, 50f, "%"),
                ParamSpec("time", "Time", 2f, 25f, 12f, "ms"),
                ParamSpec("lowFreq", "Lower Freq", 100f, 2000f, 300f, "Hz", ParamScale.LOG),
            ),
            factory = ::StereoSpread,
        )
    }
}

/** Logic "Gain": level, phase invert, mono and channel swap utility. */
class GainPlugin(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val g = dbToGain(param("gain"))
        val il = if (param("invertL") > 0.5f) -1f else 1f
        val ir = if (param("invertR") > 0.5f) -1f else 1f
        val mono = param("mono") > 0.5f
        val swap = param("swap") > 0.5f
        for (i in offset until offset + frames) {
            var l = left[i] * g * il
            var r = right[i] * g * ir
            if (swap) { val t = l; l = r; r = t }
            if (mono) { val m = (l + r) * 0.5f; l = m; r = m }
            left[i] = l; right[i] = r
        }
    }

    companion object {
        private val OFF_ON = listOf("Off", "On")
        val DESCRIPTOR = PluginDescriptor(
            id = "gain", name = "Gain", category = PluginCategory.UTILITY,
            description = "Level, phase invert, mono, swap",
            params = listOf(
                ParamSpec("gain", "Gain", -48f, 24f, 0f, "dB"),
                ParamSpec("invertL", "Phase Invert L", 0f, 1f, 0f, choices = OFF_ON),
                ParamSpec("invertR", "Phase Invert R", 0f, 1f, 0f, choices = OFF_ON),
                ParamSpec("mono", "Mono", 0f, 1f, 0f, choices = OFF_ON),
                ParamSpec("swap", "Swap L/R", 0f, 1f, 0f, choices = OFF_ON),
            ),
            factory = ::GainPlugin,
        )
    }
}

/** Logic "Enveloper": transient shaper (attack / sustain emphasis). */
class Enveloper(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val fast = EnvelopeFollower(sr).apply { set(0.5f, 60f) }
    private val slow = EnvelopeFollower(sr).apply { set(25f, 250f) }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val attack = param("attack") / 100f
        val sustain = param("sustain") / 100f
        val out = dbToGain(param("output"))
        for (i in offset until offset + frames) {
            val f = fast.processStereo(left[i], right[i])
            val s = slow.processStereo(left[i], right[i])
            val transient = if (s > 1e-6f) (f / s).coerceIn(0.25f, 4f) else 1f
            val tail = if (f > 1e-6f) (s / f).coerceIn(0.25f, 4f) else 1f
            val g = transient.pow(attack) * tail.pow(sustain) * out
            left[i] *= g; right[i] *= g
        }
    }

    override fun reset() { fast.reset(); slow.reset() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "enveloper", name = "Enveloper", category = PluginCategory.DYNAMICS,
            description = "Transient designer: boost or soften attacks and tails",
            params = listOf(
                ParamSpec("attack", "Attack", -100f, 100f, 30f, "%"),
                ParamSpec("sustain", "Sustain", -100f, 100f, 0f, "%"),
                ParamSpec("output", "Output", -12f, 12f, 0f, "dB"),
            ),
            factory = ::Enveloper,
        )
    }
}
