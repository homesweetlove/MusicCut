package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.Biquad
import com.musiccut.core.dsp.DelayLine
import com.musiccut.core.dsp.FilterType
import com.musiccut.core.dsp.LoudnessMeter
import com.musiccut.core.dsp.PitchDetector
import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.dsp.sanitize
import com.musiccut.core.dsp.timeCoef
import com.musiccut.core.plugin.AnalyzerTap
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.ParamScale
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.mixWet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** ISO 1/3-octave band centres used by the Graphic / Match EQ. */
val THIRD_OCTAVE = floatArrayOf(
    20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
    800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
)

/**
 * 31-band graphic EQ. Also the engine of Logic's "Match EQ": the app measures a reference and the
 * current material and writes the difference curve into the band gains.
 */
open class GraphicEq(sr: Int, private val desc: () -> PluginDescriptor = { GRAPHIC }) : AudioPlugin(sr) {
    override val descriptor get() = desc()
    private val bands = Array(THIRD_OCTAVE.size) { Biquad() }
    private val active = BooleanArray(THIRD_OCTAVE.size)
    private var out = 1f
    private val tap = AnalyzerTap()
    override val analyzer get() = tap

    override fun update() {
        val amount = param("amount") / 100f
        for (b in bands.indices) {
            val f = THIRD_OCTAVE[b]
            val g = param("g$b") * amount
            active[b] = abs(g) > 0.05f && f < sampleRate * 0.45f
            if (active[b]) bands[b].set(FilterType.PEAK, sampleRate, f, 4.3f, g)
        }
        out = dbToGain(param("output"))
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (b in bands.indices) if (active[b]) bands[b].process(left, right, offset, frames)
        if (out != 1f) for (i in offset until offset + frames) { left[i] *= out; right[i] *= out }
        tap.push(left, right, offset, frames)
    }

    override fun reset() = bands.forEach { it.reset() }

    companion object {
        fun bandParams(): List<ParamSpec> = THIRD_OCTAVE.mapIndexed { i, f ->
            ParamSpec("g$i", if (f >= 1000) "${f / 1000}k" else "${f.toInt()}", -12f, 12f, 0f, "dB")
        }

        val GRAPHIC: PluginDescriptor = PluginDescriptor(
            id = "graphic_eq", name = "Graphic EQ", category = PluginCategory.EQ,
            description = "31-band 1/3-octave graphic equalizer",
            params = bandParams() + listOf(
                ParamSpec("amount", "Amount", 0f, 150f, 100f, "%"),
                ParamSpec("output", "Output", -12f, 12f, 0f, "dB"),
            ),
            presets = mapOf(
                "Smile Curve" to mapOf("g3" to 4f, "g4" to 4f, "g5" to 3f, "g14" to -2f, "g15" to -2f, "g26" to 3f, "g27" to 4f),
                "Radio" to (0..30).associate { "g$it" to if (it < 9 || it > 24) -12f else 2f },
            ),
            factory = { GraphicEq(it) },
        )

        val MATCH: PluginDescriptor = PluginDescriptor(
            id = "match_eq", name = "Match EQ", category = PluginCategory.EQ,
            description = "Matches the tonal balance of a reference (learn template & current)",
            params = bandParams() + listOf(
                ParamSpec("amount", "Apply", 0f, 150f, 100f, "%"),
                ParamSpec("output", "Output", -12f, 12f, 0f, "dB"),
            ),
            factory = { sr -> GraphicEq(sr) { MATCH } },
        )
    }
}

/** Logic "Ringshifter": ring modulator or frequency shifter (Hilbert transform). */
class Ringshifter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private var phase = 0.0
    // Hilbert transformer: two 4-stage all-pass chains (Olli Niemitalo coefficients)
    private val coefA = floatArrayOf(0.6923878f, 0.9360654322959f, 0.9882295226860f, 0.9987488452737f)
    private val coefB = floatArrayOf(0.4021921162426f, 0.8561710882420f, 0.9722909545651f, 0.9952884791278f)
    private val stA = Array(2) { FloatArray(8) }
    private val stB = Array(2) { FloatArray(8) }
    private val delayB = FloatArray(2)
    private var fbL = 0f; private var fbR = 0f

    private fun allpassChain(x: Float, coef: FloatArray, st: FloatArray): Float {
        var v = x
        for (i in coef.indices) {
            val a = coef[i] * coef[i]
            val y = a * (v - st[2 * i + 1]) + st[2 * i]
            st[2 * i] = v
            st[2 * i + 1] = sanitize(y)
            v = y
        }
        return v
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val mode = param("mode").toInt()
        val f = param("frequency")
        val fb = param("feedback") / 100f
        val mix = param("mix")
        val inc = 2.0 * PI * f / sampleRate
        for (i in offset until offset + frames) {
            val c = cos(phase).toFloat(); val s = sin(phase).toFloat()
            phase += inc
            if (phase > 2 * PI) phase -= 2 * PI
            val xl = left[i] + fbL * fb
            val xr = right[i] + fbR * fb
            val yl: Float
            val yr: Float
            if (mode == 0) {
                yl = xl * c; yr = xr * c
            } else {
                val iL = allpassChain(xl, coefA, stA[0]); val qL = delayB[0]; delayB[0] = allpassChain(xl, coefB, stB[0])
                val iR = allpassChain(xr, coefA, stA[1]); val qR = delayB[1]; delayB[1] = allpassChain(xr, coefB, stB[1])
                val sign = if (mode == 1) 1f else -1f
                yl = iL * c - sign * qL * s
                yr = iR * c - sign * qR * s
            }
            fbL = sanitize(yl); fbR = sanitize(yr)
            left[i] = mixWet(left[i], yl, mix)
            right[i] = mixWet(right[i], yr, mix)
        }
    }

    override fun reset() { stA.forEach { it.fill(0f) }; stB.forEach { it.fill(0f) }; delayB.fill(0f); fbL = 0f; fbR = 0f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "ringshifter", name = "Ringshifter", category = PluginCategory.MODULATION,
            description = "Ring modulator / frequency shifter",
            params = listOf(
                ParamSpec("mode", "Mode", 0f, 2f, 1f, choices = listOf("Ring Mod", "Shift Up", "Shift Down")),
                ParamSpec("frequency", "Frequency", 0.1f, 2000f, 30f, "Hz", ParamScale.LOG),
                ParamSpec("feedback", "Feedback", 0f, 90f, 0f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 50f, "%"),
            ),
            factory = ::Ringshifter,
        )
    }
}

/** Logic "Ensemble": multi-voice chorus with random and sine modulation. */
class Ensemble(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val dl = DelayLine((sr * 0.05f).toInt())
    private val dr = DelayLine((sr * 0.05f).toInt())
    private val phases = DoubleArray(8) { it / 8.0 }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val voices = param("voices").toInt().coerceIn(2, 8)
        val rate = param("rate")
        val depth = param("depth") / 100f * 0.007f * sampleRate
        val base = 0.012f * sampleRate
        val mix = param("mix")
        val spread = param("spread") / 100f
        for (i in offset until offset + frames) {
            dl.push(left[i]); dr.push(right[i])
            var wl = 0f; var wr = 0f
            for (v in 0 until voices) {
                phases[v] += rate * (1 + v * 0.13) / sampleRate
                if (phases[v] >= 1) phases[v] -= 1.0
                val m = sin(2 * PI * phases[v]).toFloat()
                val d = base + depth * (0.5f + 0.5f * m) + v * 0.0007f * sampleRate
                val pan = if (voices > 1) (v.toFloat() / (voices - 1) * 2 - 1) * spread else 0f
                val sL = (dl.read(d) + dr.read(d)) * 0.5f
                wl += sL * (1 - pan) * 0.5f
                wr += sL * (1 + pan) * 0.5f
            }
            val g = 2f / voices
            left[i] = mixWet(left[i], wl * g, mix)
            right[i] = mixWet(right[i], wr * g, mix)
        }
    }

    override fun reset() { dl.clear(); dr.clear() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "ensemble", name = "Ensemble", category = PluginCategory.MODULATION,
            description = "Rich multi-voice chorus",
            params = listOf(
                ParamSpec("voices", "Voices", 2f, 8f, 4f, ""),
                ParamSpec("rate", "Rate", 0.05f, 5f, 0.5f, "Hz", ParamScale.LOG),
                ParamSpec("depth", "Intensity", 0f, 100f, 50f, "%"),
                ParamSpec("spread", "Spread", 0f, 100f, 100f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 50f, "%"),
            ),
            factory = ::Ensemble,
        )
    }
}

/** Logic "ChromaGlow": saturation with tube / tape character models. */
class ChromaGlow(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val tilt = Biquad()
    private val hp = Biquad()
    private var drive = 1f

    override fun update() {
        drive = dbToGain(param("drive"))
        val model = param("model").toInt()
        val tone = param("tone")
        tilt.set(FilterType.HIGH_SHELF, sampleRate, 3000f, 0.707f, tone + if (model == 2) -2f else 0f)
        hp.set(FilterType.HIGH_PASS, sampleRate, 25f, 0.707f)
    }

    private fun shape(x: Float, model: Int): Float = when (model) {
        0 -> tanh(x)                                  // Modern Tube
        1 -> tanh(x + 0.2f * x * x) - 0.2f * tanh(0.2f) // Vintage Tube (even harmonics)
        2 -> x / (1f + abs(x))                        // Tape
        else -> (1.5f * x - 0.5f * x * x * x).coerceIn(-1f, 1f) // Retro (cubic)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val model = param("model").toInt()
        val out = dbToGain(param("output"))
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val l = left[i]; val r = right[i]
            val wl = tilt.processL(hp.processL(shape(l * drive, model))) * out
            val wr = tilt.processR(hp.processR(shape(r * drive, model))) * out
            left[i] = mixWet(l, wl, mix); right[i] = mixWet(r, wr, mix)
        }
    }

    override fun reset() { tilt.reset(); hp.reset() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "chromaglow", name = "ChromaGlow", category = PluginCategory.DISTORTION,
            description = "Analog warmth: tube and tape saturation models",
            params = listOf(
                ParamSpec("model", "Model", 0f, 3f, 0f, choices = listOf("Modern Tube", "Vintage Tube", "Magnetic Tape", "Retro Tube")),
                ParamSpec("drive", "Drive", 0f, 24f, 6f, "dB"),
                ParamSpec("tone", "Tone", -6f, 6f, 0f, "dB"),
                ParamSpec("output", "Output", -24f, 6f, -4f, "dB"),
                ParamSpec("mix", "Mix", 0f, 100f, 100f, "%"),
            ),
            factory = ::ChromaGlow,
        )
    }
}

/** Logic "Amp Designer" lite: preamp gain, 3-band tone stack, presence and a speaker cabinet. */
class AmpDesigner(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val preHp = Biquad()
    private val bass = Biquad(); private val mid = Biquad(); private val treble = Biquad(); private val presence = Biquad()
    private val cabLp = Biquad(); private val cabHp = Biquad(); private val cabRes = Biquad()
    private var gain = 1f

    override fun update() {
        gain = dbToGain(param("gain"))
        preHp.set(FilterType.HIGH_PASS, sampleRate, 90f, 0.707f)
        bass.set(FilterType.LOW_SHELF, sampleRate, 120f, 0.707f, param("bass"))
        mid.set(FilterType.PEAK, sampleRate, 750f, 0.8f, param("mid"))
        treble.set(FilterType.HIGH_SHELF, sampleRate, 3000f, 0.707f, param("treble"))
        presence.set(FilterType.PEAK, sampleRate, 4500f, 1.2f, param("presence"))
        val cab = param("cabinet").toInt()
        cabLp.set(FilterType.LOW_PASS, sampleRate, floatArrayOf(5500f, 4200f, 3200f)[cab], 0.9f)
        cabHp.set(FilterType.HIGH_PASS, sampleRate, floatArrayOf(80f, 70f, 100f)[cab], 0.707f)
        cabRes.set(FilterType.PEAK, sampleRate, floatArrayOf(110f, 95f, 160f)[cab], 1.5f, 4f)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val out = dbToGain(param("output"))
        for (i in offset until offset + frames) {
            val x = (left[i] + right[i]) * 0.5f
            var y = tanh(preHp.processL(x) * gain)
            y = tanh(y * 1.5f)
            y = presence.processL(treble.processL(mid.processL(bass.processL(y))))
            y = cabRes.processL(cabHp.processL(cabLp.processL(y))) * out
            left[i] = y; right[i] = y
        }
    }

    override fun reset() { listOf(preHp, bass, mid, treble, presence, cabLp, cabHp, cabRes).forEach { it.reset() } }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "amp_designer", name = "Amp Designer", category = PluginCategory.DISTORTION,
            description = "Guitar / bass amp and cabinet simulation",
            params = listOf(
                ParamSpec("gain", "Gain", 0f, 50f, 20f, "dB"),
                ParamSpec("bass", "Bass", -12f, 12f, 2f, "dB"),
                ParamSpec("mid", "Mids", -12f, 12f, 0f, "dB"),
                ParamSpec("treble", "Treble", -12f, 12f, 1f, "dB"),
                ParamSpec("presence", "Presence", -12f, 12f, 2f, "dB"),
                ParamSpec("cabinet", "Cabinet", 0f, 2f, 1f, choices = listOf("1x12 Open", "4x12 Closed", "2x10 Vintage")),
                ParamSpec("output", "Master", -40f, 0f, -12f, "dB"),
            ),
            presets = mapOf(
                "Clean" to mapOf("gain" to 4f, "output" to -6f),
                "Crunch" to mapOf("gain" to 22f, "mid" to 3f),
                "High Gain" to mapOf("gain" to 45f, "mid" to -4f, "bass" to 4f, "output" to -18f),
            ),
            factory = ::AmpDesigner,
        )
    }
}

/** Logic "Adaptive Limiter": smooth, loudness-oriented soft-knee limiter. */
class AdaptiveLimiter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val look = (sr * 0.003f).toInt()
    private val dl = DelayLine(look + 8)
    private val dr = DelayLine(look + 8)
    private var env = 0f
    private var g = 1f
    override val latency: Int get() = look
    override val gainReductionDb: Float get() = 20f * kotlin.math.log10(g.coerceAtLeast(1e-4f))

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val input = dbToGain(param("gain"))
        val ceiling = dbToGain(param("ceiling"))
        val rel = timeCoef(param("release"), sampleRate)
        val att = timeCoef(1.5f, sampleRate)
        for (i in offset until offset + frames) {
            val l = left[i] * input; val r = right[i] * input
            dl.push(l); dr.push(r)
            val peak = max(abs(l), abs(r))
            env = if (peak > env) peak + (env - peak) * att else peak + (env - peak) * rel
            val target = if (env > ceiling * 0.7f) (ceiling * 0.7f + (env - ceiling * 0.7f) * 0.1f).coerceAtMost(ceiling) / env else 1f
            g = if (target < g) target + (g - target) * att else target + (g - target) * rel
            val ol = dl.readInt(look) * g; val or = dr.readInt(look) * g
            left[i] = ceiling * tanh(ol / ceiling)
            right[i] = ceiling * tanh(or / ceiling)
        }
    }

    override fun reset() { dl.clear(); dr.clear(); env = 0f; g = 1f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "adaptive_limiter", name = "Adaptive Limiter", category = PluginCategory.DYNAMICS,
            description = "Warm, musical limiting for loud masters",
            params = listOf(
                ParamSpec("gain", "Input Scale", 0f, 18f, 3f, "dB"),
                ParamSpec("ceiling", "Out Ceiling", -6f, 0f, -1f, "dB"),
                ParamSpec("release", "Release", 10f, 500f, 120f, "ms", ParamScale.LOG),
            ),
            factory = ::AdaptiveLimiter,
        )
    }
}

/** Logic "Delay Designer": four independent taps with level, pan, filter and feedback. */
class DelayDesigner(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val line = DelayLine(sr * 2 + 16)
    private val lp = Biquad()
    private var fbSample = 0f
    private val times = IntArray(4)
    private val levels = FloatArray(4)
    private val pans = FloatArray(4)

    override fun update() {
        lp.set(FilterType.LOW_PASS, sampleRate, param("cutoff"), 0.707f)
        for (t in 0 until 4) {
            times[t] = (param("t${t + 1}") * 0.001f * sampleRate).toInt().coerceIn(1, sampleRate * 2)
            levels[t] = param("l${t + 1}") / 100f
            pans[t] = param("p${t + 1}") / 100f
        }
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val fb = param("feedback") / 100f
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val x = (left[i] + right[i]) * 0.5f
            line.push(x + fbSample * fb)
            var wl = 0f; var wr = 0f
            for (t in 0 until 4) {
                val v = line.readInt(times[t] - 1) * levels[t]
                wl += v * (1 - pans[t]).coerceAtMost(1f)
                wr += v * (1 + pans[t]).coerceAtMost(1f)
            }
            fbSample = sanitize(lp.processL(line.readInt(times[3] - 1)))
            left[i] = mixWet(left[i], wl, mix)
            right[i] = mixWet(right[i], wr, mix)
        }
    }

    override fun reset() { line.clear(); lp.reset(); fbSample = 0f }

    companion object {
        private val tapDefaults = listOf(125f, 250f, 375f, 500f)
        val DESCRIPTOR = PluginDescriptor(
            id = "delay_designer", name = "Delay Designer", category = PluginCategory.DELAY,
            description = "Multi-tap delay with per-tap level and pan",
            params = (1..4).flatMap { t ->
                listOf(
                    ParamSpec("t$t", "Tap $t Time", 1f, 2000f, tapDefaults[t - 1], "ms", ParamScale.LOG),
                    ParamSpec("l$t", "Tap $t Level", 0f, 100f, 100f - t * 18f, "%"),
                    ParamSpec("p$t", "Tap $t Pan", -100f, 100f, if (t % 2 == 0) 60f else -60f, ""),
                )
            } + listOf(
                ParamSpec("feedback", "Feedback", 0f, 90f, 20f, "%"),
                ParamSpec("cutoff", "Feedback Cutoff", 500f, 16000f, 5000f, "Hz", ParamScale.LOG),
                ParamSpec("mix", "Mix", 0f, 100f, 30f, "%"),
            ),
            factory = ::DelayDesigner,
        )
    }
}

/** Logic "Loudness Meter": EBU R128 momentary / short-term / integrated LUFS and true peak. */
class LoudnessMeterPlugin(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val meter = LoudnessMeter(sr)
    @Volatile private var integrated = -70f
    private var counter = 0

    override val readouts: Map<String, Float>
        get() = mapOf("momentary" to meter.momentaryLufs, "shortTerm" to meter.shortTermLufs, "integrated" to integrated, "truePeak" to meter.truePeakDb)

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        meter.process(left, right, offset, frames)
        counter += frames
        if (counter > sampleRate) { counter = 0; integrated = meter.integratedLufs() }
    }

    override fun reset() { meter.reset(); integrated = -70f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "loudness_meter", name = "Loudness Meter", category = PluginCategory.UTILITY,
            description = "LUFS (EBU R128) and true-peak metering",
            params = listOf(ParamSpec("target", "Target", -30f, -6f, -14f, "LUFS")),
            factory = ::LoudnessMeterPlugin,
        )
    }
}

/** Logic "MultiMeter": spectrum analyzer, peak levels and phase correlation. */
class MultiMeter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val tap = AnalyzerTap()
    override val analyzer get() = tap
    @Volatile private var corr = 1f
    @Volatile private var pkL = 0f
    @Volatile private var pkR = 0f
    private var sLR = 0.0; private var sLL = 0.0; private var sRR = 0.0
    override val readouts: Map<String, Float> get() = mapOf("correlation" to corr, "peakL" to pkL, "peakR" to pkR)

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        tap.push(left, right, offset, frames)
        var pl = 0f; var pr = 0f
        for (i in offset until offset + frames) {
            val l = left[i]; val r = right[i]
            sLR = sLR * 0.9995 + l * r; sLL = sLL * 0.9995 + l * l; sRR = sRR * 0.9995 + r * r
            pl = max(pl, abs(l)); pr = max(pr, abs(r))
        }
        corr = if (sLL * sRR > 1e-12) (sLR / sqrt(sLL * sRR)).toFloat() else 1f
        pkL = pl; pkR = pr
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "multimeter", name = "MultiMeter", category = PluginCategory.UTILITY,
            description = "Analyzer, peak meter and correlation meter",
            params = listOf(ParamSpec("hold", "Peak Hold", 0f, 1f, 1f, choices = listOf("Off", "On"))),
            factory = ::MultiMeter,
        )
    }
}

/** Logic "Tuner": shows the detected note and cents deviation. */
class TunerPlugin(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val det = PitchDetector(sr, 2048, 0.15f)
    private val buf = FloatArray(2048)
    private var pos = 0
    @Volatile private var midi = 0f
    override val readouts: Map<String, Float> get() = mapOf("midi" to midi, "reference" to param("reference"))

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (i in offset until offset + frames) {
            buf[pos++] = (left[i] + right[i]) * 0.5f
            if (pos == buf.size) {
                pos = 0
                val hz = det.detect(buf, 0, 30f, 1500f)
                if (hz > 0) midi = PitchDetector.hzToMidi(hz * 440f / param("reference"))
            }
        }
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "tuner", name = "Tuner", category = PluginCategory.UTILITY,
            description = "Chromatic tuner",
            params = listOf(ParamSpec("reference", "Reference", 430f, 450f, 440f, "Hz")),
            factory = ::TunerPlugin,
        )
    }
}

/** Logic "Test Oscillator": sine, square, noise or sweep generator (replaces the input). */
class TestOscillator(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private var phase = 0.0
    private var sweepT = 0.0
    private val rnd = Random(7)

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val wave = param("wave").toInt()
        val f = param("frequency").toDouble()
        val level = dbToGain(param("level"))
        for (i in offset until offset + frames) {
            val freq = if (wave == 3) {
                sweepT += 1.0 / sampleRate
                if (sweepT > 10.0) sweepT = 0.0
                20.0 * 1000.0.pow(sweepT / 10.0)
            } else f
            phase += freq / sampleRate
            if (phase >= 1) phase -= 1.0
            val v = when (wave) {
                1 -> if (phase < 0.5) 1f else -1f
                2 -> rnd.nextFloat() * 2 - 1
                else -> sin(2 * PI * phase).toFloat()
            } * level
            left[i] = v; right[i] = v
        }
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "test_oscillator", name = "Test Oscillator", category = PluginCategory.UTILITY,
            description = "Signal generator for testing and tuning",
            params = listOf(
                ParamSpec("wave", "Waveform", 0f, 3f, 0f, choices = listOf("Sine", "Square", "Noise", "Sweep")),
                ParamSpec("frequency", "Frequency", 20f, 20000f, 1000f, "Hz", ParamScale.LOG),
                ParamSpec("level", "Level", -60f, 0f, -18f, "dB"),
            ),
            factory = ::TestOscillator,
        )
    }
}

