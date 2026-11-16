package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.Biquad
import com.musiccut.core.dsp.DelayLine
import com.musiccut.core.dsp.FilterType
import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.dsp.gainToDb
import com.musiccut.core.dsp.timeCoef
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.ParamScale
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.mixWet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Gain computer with soft knee shared by compressor-style plugins. Returns gain reduction in dB (<= 0). */
internal fun compressorCurve(levelDb: Float, thresholdDb: Float, ratio: Float, kneeDb: Float): Float {
    val over = levelDb - thresholdDb
    val slope = 1f / ratio - 1f
    return when {
        kneeDb > 0f && abs(over) <= kneeDb / 2f -> slope * (over + kneeDb / 2f) * (over + kneeDb / 2f) / (2f * kneeDb)
        over > 0f -> slope * over
        else -> 0f
    }
}

/** Logic "Compressor" (VCA-style, stereo linked, soft knee, auto make-up). */
class Compressor(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private var att = 0f
    private var rel = 0f
    private var envDb = 0f
    private var makeup = 1f
    @Volatile private var grDb = 0f
    override val gainReductionDb: Float get() = grDb

    override fun update() {
        att = timeCoef(param("attack") * if (param("circuit").toInt() == 2) 0.3f else 1f, sampleRate)
        rel = timeCoef(param("release"), sampleRate)
        scHp.set(FilterType.HIGH_PASS, sampleRate, param("scFilter"), 0.707f)
        val auto = if (param("autoGain") > 0.5f) -compressorCurve(0f, param("threshold"), param("ratio"), param("knee")) * 0.5f else 0f
        makeup = dbToGain(param("makeup") + auto)
    }

    private var rms = 0f
    private val rmsCoef = timeCoef(10f, sr)
    private val scHp = Biquad()

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val thr = param("threshold"); val ratio = param("ratio"); val knee = param("knee"); val mix = param("mix")
        val circuit = param("circuit").toInt()
        val keyL = sidechainL; val keyR = sidechainR
        var maxGr = 0f
        for (i in offset until offset + frames) {
            val l = left[i]; val r = right[i]
            val kl = if (keyL != null) keyL[i] else l
            val kr = if (keyR != null) keyR[i] else r
            var det = max(abs(scHp.processL(kl)), abs(scHp.processR(kr)))
            // circuit models: 0 Platinum (peak), 1 VCA (peak, hard knee), 2 FET (fast, bright),
            // 3 Opto (RMS, program-dependent release), 4 Vintage Classic (RMS, soft)
            if (circuit == 3 || circuit == 4) {
                rms = rms * rmsCoef + det * det * (1 - rmsCoef)
                det = kotlin.math.sqrt(rms) * 1.41f
            }
            val lvl = gainToDb(det)
            val k = if (circuit == 1) 0f else if (circuit == 4) knee + 6f else knee
            val target = compressorCurve(lvl, thr, ratio, k)
            val relC = if (circuit == 3) timeCoef(param("release") * (1f - envDb / 20f).coerceIn(1f, 4f), sampleRate) else rel
            envDb = if (target < envDb) target + (envDb - target) * att else target + (envDb - target) * relC
            val g = dbToGain(envDb) * makeup
            var ol = l * g
            var or = r * g
            if (circuit == 2) { ol = kotlin.math.tanh(ol * 1.1f) / 1.1f; or = kotlin.math.tanh(or * 1.1f) / 1.1f }
            left[i] = mixWet(l, ol, mix)
            right[i] = mixWet(r, or, mix)
            maxGr = min(maxGr, envDb)
        }
        grDb = maxGr
    }

    override fun reset() { envDb = 0f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "compressor", name = "Compressor", category = PluginCategory.DYNAMICS,
            description = "Classic VCA compressor with soft knee and parallel mix",
            params = listOf(
                ParamSpec("threshold", "Threshold", -60f, 0f, -18f, "dB"),
                ParamSpec("ratio", "Ratio", 1f, 30f, 4f, ":1", ParamScale.LOG),
                ParamSpec("attack", "Attack", 0.1f, 200f, 10f, "ms", ParamScale.LOG),
                ParamSpec("release", "Release", 5f, 2000f, 120f, "ms", ParamScale.LOG),
                ParamSpec("knee", "Knee", 0f, 24f, 6f, "dB"),
                ParamSpec("makeup", "Make-Up", -12f, 30f, 0f, "dB"),
                ParamSpec("autoGain", "Auto Gain", 0f, 1f, 1f, choices = listOf("Off", "On")),
                ParamSpec("mix", "Mix", 0f, 100f, 100f, "%"),
                ParamSpec("circuit", "Circuit Type", 0f, 4f, 0f, choices = listOf("Platinum", "VCA", "FET", "Opto", "Classic")),
                ParamSpec("scFilter", "Side Chain HPF", 20f, 500f, 20f, "Hz", ParamScale.LOG),
            ),
            presets = mapOf(
                "Vocal Leveler" to mapOf("threshold" to -22f, "ratio" to 3f, "attack" to 8f, "release" to 150f, "circuit" to 3f),
                "FET Vocal (1176 style)" to mapOf("threshold" to -24f, "ratio" to 4f, "attack" to 0.8f, "release" to 60f, "circuit" to 2f),
                "Kick Ducking (side chain)" to mapOf("threshold" to -30f, "ratio" to 8f, "attack" to 1f, "release" to 120f, "knee" to 0f, "autoGain" to 0f),
                "Drum Punch" to mapOf("threshold" to -16f, "ratio" to 6f, "attack" to 25f, "release" to 80f),
                "Bus Glue" to mapOf("threshold" to -12f, "ratio" to 2f, "attack" to 30f, "release" to 300f, "knee" to 12f),
                "Parallel Smash" to mapOf("threshold" to -35f, "ratio" to 20f, "attack" to 1f, "release" to 60f, "mix" to 40f),
            ),
            factory = ::Compressor,
        )
    }
}

/** Logic "Limiter" / "Adaptive Limiter" lite: look-ahead brickwall limiter. */
class Limiter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val lookahead = (sr * 0.0015f).toInt().coerceAtLeast(1)
    private val dl = DelayLine(lookahead + 8)
    private val dr = DelayLine(lookahead + 8)
    private val peakWindow = FloatArray(lookahead + 1)
    private var peakPos = 0
    private var gainEnv = 1f
    private var rel = 0f
    private var inGain = 1f
    private var ceiling = 1f
    private val attackCoef = timeCoef(0.5f, sr)

    override val latency: Int get() = lookahead
    override val gainReductionDb: Float get() = gainToDb(gainEnv)

    override fun update() {
        rel = timeCoef(param("release"), sampleRate)
        inGain = dbToGain(param("gain"))
        ceiling = dbToGain(param("ceiling"))
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (i in offset until offset + frames) {
            val l = left[i] * inGain
            val r = right[i] * inGain
            dl.push(l); dr.push(r)
            peakWindow[peakPos] = max(abs(l), abs(r))
            peakPos = (peakPos + 1) % peakWindow.size
            var peak = 0f
            for (p in peakWindow) if (p > peak) peak = p
            val target = if (peak > ceiling) ceiling / peak else 1f
            gainEnv = if (target < gainEnv) target + (gainEnv - target) * attackCoef else target + (gainEnv - target) * rel
            val ol = dl.readInt(lookahead) * gainEnv
            val or = dr.readInt(lookahead) * gainEnv
            left[i] = ol.coerceIn(-ceiling, ceiling)
            right[i] = or.coerceIn(-ceiling, ceiling)
        }
    }

    override fun reset() { dl.clear(); dr.clear(); peakWindow.fill(0f); gainEnv = 1f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "limiter", name = "Limiter", category = PluginCategory.DYNAMICS,
            description = "Look-ahead brickwall limiter for mastering",
            params = listOf(
                ParamSpec("gain", "Gain", 0f, 24f, 0f, "dB"),
                ParamSpec("ceiling", "Output Ceiling", -12f, 0f, -0.3f, "dB"),
                ParamSpec("release", "Release", 1f, 1000f, 60f, "ms", ParamScale.LOG),
            ),
            presets = mapOf(
                "Loud Master" to mapOf("gain" to 6f, "ceiling" to -1f, "release" to 80f),
                "Safety" to mapOf("gain" to 0f, "ceiling" to -0.3f, "release" to 50f),
            ),
            factory = ::Limiter,
        )
    }
}

/** Logic "Noise Gate". */
class NoiseGate(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private var env = 0f
    private var gain = 0f
    private var holdCounter = 0
    private var att = 0f
    private var rel = 0f
    private var envRel = timeCoef(20f, sr)
    private var thr = 0f
    private var floor = 0f
    private var holdSamples = 0
    override val gainReductionDb: Float get() = gainToDb(gain)

    override fun update() {
        att = timeCoef(param("attack"), sampleRate)
        rel = timeCoef(param("release"), sampleRate)
        thr = dbToGain(param("threshold"))
        floor = dbToGain(param("reduction"))
        holdSamples = (param("hold") * 0.001f * sampleRate).toInt()
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val keyL = sidechainL; val keyR = sidechainR
        for (i in offset until offset + frames) {
            val x = if (keyL != null && keyR != null) max(abs(keyL[i]), abs(keyR[i])) else max(abs(left[i]), abs(right[i]))
            env = if (x > env) x else x + (env - x) * envRel
            val open = if (env >= thr) {
                holdCounter = holdSamples; true
            } else if (holdCounter > 0) {
                holdCounter--; true
            } else false
            val target = if (open) 1f else floor
            gain = if (target > gain) target + (gain - target) * att else target + (gain - target) * rel
            left[i] *= gain
            right[i] *= gain
        }
    }

    override fun reset() { env = 0f; gain = 0f; holdCounter = 0 }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "noise_gate", name = "Noise Gate", category = PluginCategory.DYNAMICS,
            description = "Removes background noise between phrases",
            params = listOf(
                ParamSpec("threshold", "Threshold", -80f, 0f, -45f, "dB"),
                ParamSpec("reduction", "Reduction", -100f, 0f, -60f, "dB"),
                ParamSpec("attack", "Attack", 0.05f, 50f, 1f, "ms", ParamScale.LOG),
                ParamSpec("hold", "Hold", 0f, 500f, 40f, "ms"),
                ParamSpec("release", "Release", 5f, 2000f, 150f, "ms", ParamScale.LOG),
            ),
            factory = ::NoiseGate,
        )
    }
}

/** Logic "DeEsser 2": split-band sibilance reduction. */
class DeEsser(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val detector = Biquad()
    private val splitter = Biquad()
    private var env = 0f
    private var att = timeCoef(1f, sr)
    private var rel = timeCoef(60f, sr)
    private var gain = 1f
    private var thr = 0f
    private var maxRed = 0f
    override val gainReductionDb: Float get() = gainToDb(gain)

    override fun update() {
        detector.set(FilterType.BAND_PASS, sampleRate, param("frequency"), 1.4f)
        splitter.set(FilterType.HIGH_PASS, sampleRate, param("frequency") * 0.7f, 0.707f)
        thr = dbToGain(param("threshold"))
        maxRed = dbToGain(-param("reduction"))
        rel = timeCoef(param("release"), sampleRate)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for (i in offset until offset + frames) {
            val l = left[i]; val r = right[i]
            val dL = detector.processL(l); val dR = detector.processR(r)
            val d = max(abs(dL), abs(dR))
            env = if (d > env) d + (env - d) * att else d + (env - d) * rel
            val target = if (env > thr) max(maxRed, thr / env) else 1f
            gain = target + (gain - target) * 0.99f
            val hL = splitter.processL(l); val hR = splitter.processR(r)
            left[i] = (l - hL) + hL * gain
            right[i] = (r - hR) + hR * gain
        }
    }

    override fun reset() { detector.reset(); splitter.reset(); env = 0f; gain = 1f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "deesser", name = "DeEsser 2", category = PluginCategory.DYNAMICS,
            description = "Tames harsh 's' and 'sh' sounds in vocals",
            params = listOf(
                ParamSpec("frequency", "Frequency", 2000f, 12000f, 6500f, "Hz", ParamScale.LOG),
                ParamSpec("threshold", "Threshold", -60f, 0f, -28f, "dB"),
                ParamSpec("reduction", "Max Reduction", 0f, 24f, 8f, "dB"),
                ParamSpec("release", "Release", 5f, 300f, 60f, "ms", ParamScale.LOG),
            ),
            factory = ::DeEsser,
        )
    }
}

/** Logic "Multipressor" lite: 3-band compressor with Linkwitz-Riley style crossovers. */
class Multipressor(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val lowLp = arrayOf(Biquad(), Biquad())
    private val midHp = arrayOf(Biquad(), Biquad())
    private val midLp = arrayOf(Biquad(), Biquad())
    private val highHp = arrayOf(Biquad(), Biquad())
    private val env = FloatArray(3) { 0f }
    private var att = 0f
    private var rel = 0f
    private val thr = FloatArray(3)
    private val bandsL = FloatArray(3)
    private val bandsR = FloatArray(3)
    override val gainReductionDb: Float get() = minOf(env[0], env[1], env[2])

    override fun update() {
        val f1 = param("lowX"); val f2 = maxOf(param("highX"), f1 * 1.5f)
        for (b in lowLp) b.set(FilterType.LOW_PASS, sampleRate, f1, 0.707f)
        for (b in midHp) b.set(FilterType.HIGH_PASS, sampleRate, f1, 0.707f)
        for (b in midLp) b.set(FilterType.LOW_PASS, sampleRate, f2, 0.707f)
        for (b in highHp) b.set(FilterType.HIGH_PASS, sampleRate, f2, 0.707f)
        att = timeCoef(param("attack"), sampleRate)
        rel = timeCoef(param("release"), sampleRate)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        thr[0] = param("lowThr"); thr[1] = param("midThr"); thr[2] = param("highThr")
        val ratio = param("ratio")
        val out = dbToGain(param("output"))
        for (i in offset until offset + frames) {
            val l = left[i]; val r = right[i]
            val lowL = lowLp[1].processL(lowLp[0].processL(l)); val lowR = lowLp[1].processR(lowLp[0].processR(r))
            val midL = midLp[1].processL(midLp[0].processL(midHp[1].processL(midHp[0].processL(l))))
            val midR = midLp[1].processR(midLp[0].processR(midHp[1].processR(midHp[0].processR(r))))
            val hiL = highHp[1].processL(highHp[0].processL(l)); val hiR = highHp[1].processR(highHp[0].processR(r))
            var oL = 0f; var oR = 0f
            bandsL[0] = lowL; bandsL[1] = midL; bandsL[2] = hiL
            bandsR[0] = lowR; bandsR[1] = midR; bandsR[2] = hiR
            for (b in 0 until 3) {
                val lvl = gainToDb(max(abs(bandsL[b]), abs(bandsR[b])))
                val target = compressorCurve(lvl, thr[b], ratio, 6f)
                env[b] = if (target < env[b]) target + (env[b] - target) * att else target + (env[b] - target) * rel
                val g = dbToGain(env[b])
                oL += bandsL[b] * g; oR += bandsR[b] * g
            }
            left[i] = oL * out
            right[i] = oR * out
        }
    }

    override fun reset() {
        (lowLp + midHp + midLp + highHp).forEach { it.reset() }
        env.fill(0f)
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "multipressor", name = "Multipressor", category = PluginCategory.DYNAMICS,
            description = "3-band multiband compressor",
            params = listOf(
                ParamSpec("lowX", "Low/Mid X-Over", 60f, 1000f, 200f, "Hz", ParamScale.LOG),
                ParamSpec("highX", "Mid/High X-Over", 1000f, 12000f, 3000f, "Hz", ParamScale.LOG),
                ParamSpec("lowThr", "Low Threshold", -60f, 0f, -20f, "dB"),
                ParamSpec("midThr", "Mid Threshold", -60f, 0f, -20f, "dB"),
                ParamSpec("highThr", "High Threshold", -60f, 0f, -20f, "dB"),
                ParamSpec("ratio", "Ratio", 1f, 20f, 3f, ":1", ParamScale.LOG),
                ParamSpec("attack", "Attack", 0.5f, 200f, 15f, "ms", ParamScale.LOG),
                ParamSpec("release", "Release", 10f, 2000f, 150f, "ms", ParamScale.LOG),
                ParamSpec("output", "Output", -12f, 12f, 0f, "dB"),
            ),
            factory = ::Multipressor,
        )
    }
}
