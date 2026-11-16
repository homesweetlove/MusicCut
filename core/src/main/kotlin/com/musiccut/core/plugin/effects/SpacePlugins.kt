package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.Biquad
import com.musiccut.core.dsp.DelayLine
import com.musiccut.core.dsp.FilterType
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
import kotlin.math.tanh

private class Comb(size: Int) {
    private val buf = FloatArray(size)
    private var idx = 0
    private var store = 0f
    var feedback = 0.8f
    var damp = 0.2f
    fun process(x: Float): Float {
        val out = buf[idx]
        store = sanitize(out * (1f - damp) + store * damp)
        buf[idx] = x + store * feedback
        if (++idx >= buf.size) idx = 0
        return out
    }
    fun clear() { buf.fill(0f); store = 0f }
}

private class AllPass(size: Int) {
    private val buf = FloatArray(size)
    private var idx = 0
    fun process(x: Float): Float {
        val b = buf[idx]
        val out = -x + b
        buf[idx] = sanitize(x + b * 0.5f)
        if (++idx >= buf.size) idx = 0
        return out
    }
    fun clear() = buf.fill(0f)
}

/** Algorithmic reverb (Freeverb topology) standing in for Logic's "ChromaVerb". */
class ChromaVerb(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val scale = sr / 44100f
    private val spread = 23
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTunings = intArrayOf(556, 441, 341, 225)
    private val combsL = Array(combTunings.size) { Comb((combTunings[it] * scale).toInt()) }
    private val combsR = Array(combTunings.size) { Comb(((combTunings[it] + spread) * scale).toInt()) }
    private val apsL = Array(apTunings.size) { AllPass((apTunings[it] * scale).toInt()) }
    private val apsR = Array(apTunings.size) { AllPass(((apTunings[it] + spread) * scale).toInt()) }
    private val pre = DelayLine((sr * 0.25f).toInt() + 4)
    private val lowCut = Biquad()
    private val highCut = Biquad()
    private var preSamples = 0
    private var wet1 = 1f
    private var wet2 = 0f

    override fun update() {
        val room = 0.7f + param("decay") / 100f * 0.28f
        val damp = param("damping") / 100f * 0.4f
        for (c in combsL + combsR) { c.feedback = room; c.damp = damp }
        preSamples = (param("predelay") * 0.001f * sampleRate).toInt()
        val width = param("width") / 100f
        wet1 = (1f + width) / 2f
        wet2 = (1f - width) / 2f
        lowCut.set(FilterType.HIGH_PASS, sampleRate, param("lowCut"), 0.707f)
        highCut.set(FilterType.LOW_PASS, sampleRate, param("highCut"), 0.707f)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val dryL = left[i]; val dryR = right[i]
            pre.push((dryL + dryR) * 0.015f)
            val inp = pre.readInt(preSamples)
            var oL = 0f; var oR = 0f
            for (c in combsL) oL += c.process(inp)
            for (c in combsR) oR += c.process(inp)
            for (a in apsL) oL = a.process(oL)
            for (a in apsR) oR = a.process(oR)
            oL = highCut.processL(lowCut.processL(oL))
            oR = highCut.processR(lowCut.processR(oR))
            val wl = oL * wet1 + oR * wet2
            val wr = oR * wet1 + oL * wet2
            left[i] = mixWet(dryL, wl, mix)
            right[i] = mixWet(dryR, wr, mix)
        }
    }

    override fun reset() {
        (combsL + combsR).forEach { it.clear() }
        (apsL + apsR).forEach { it.clear() }
        pre.clear(); lowCut.reset(); highCut.reset()
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "chromaverb", name = "ChromaVerb", category = PluginCategory.REVERB,
            description = "Algorithmic room / hall / plate reverb",
            params = listOf(
                ParamSpec("decay", "Decay", 0f, 100f, 60f, "%"),
                ParamSpec("damping", "Damping", 0f, 100f, 50f, "%"),
                ParamSpec("predelay", "Pre-Delay", 0f, 200f, 20f, "ms"),
                ParamSpec("width", "Width", 0f, 100f, 100f, "%"),
                ParamSpec("lowCut", "Low Cut", 20f, 1000f, 150f, "Hz", ParamScale.LOG),
                ParamSpec("highCut", "High Cut", 1000f, 20000f, 9000f, "Hz", ParamScale.LOG),
                ParamSpec("mix", "Mix", 0f, 100f, 25f, "%"),
            ),
            presets = mapOf(
                "Small Room" to mapOf("decay" to 25f, "damping" to 60f, "predelay" to 5f, "mix" to 18f),
                "Vocal Plate" to mapOf("decay" to 65f, "damping" to 35f, "predelay" to 30f, "lowCut" to 250f, "mix" to 22f),
                "Big Hall" to mapOf("decay" to 92f, "damping" to 40f, "predelay" to 45f, "mix" to 30f),
                "Ambient Wash" to mapOf("decay" to 99f, "damping" to 20f, "predelay" to 80f, "mix" to 55f),
            ),
            factory = ::ChromaVerb,
        )
    }
}

/** Logic "Tape Delay": filtered feedback, wow & flutter and soft saturation. */
class TapeDelay(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val dl = DelayLine(sr * 2 + 64)
    private val dr = DelayLine(sr * 2 + 64)
    private val lowCut = Biquad()
    private val highCut = Biquad()
    private val lfo = Lfo(sr)
    private var delaySamples = 1f
    private var smoothDelay = 1f

    private var syncedTempo = 0f

    override fun update() {
        delaySamples = syncedMs(param("sync"), hostTempo, param("time")) * 0.001f * sampleRate
        syncedTempo = hostTempo
        lowCut.set(FilterType.HIGH_PASS, sampleRate, param("lowCut"), 0.707f)
        highCut.set(FilterType.LOW_PASS, sampleRate, param("highCut"), 0.707f)
        lfo.rateHz = param("flutterRate")
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        if (syncedTempo != hostTempo) delaySamples = syncedMs(param("sync"), hostTempo, param("time")) * 0.001f * sampleRate
        syncedTempo = hostTempo
        val fb = param("feedback") / 100f
        val depth = param("flutterDepth") / 100f * 0.003f * sampleRate
        val mix = param("mix")
        for (i in offset until offset + frames) {
            smoothDelay += (delaySamples - smoothDelay) * 0.0005f
            val mod = sin(2 * PI * lfo.next()).toFloat() * depth
            val d = (smoothDelay + mod).coerceAtLeast(1f)
            val wl = dl.read(d); val wr = dr.read(d)
            val fl = tanh(highCut.processL(lowCut.processL(wl)) * fb * 1.2f)
            val fr = tanh(highCut.processR(lowCut.processR(wr)) * fb * 1.2f)
            dl.push(left[i] + fl)
            dr.push(right[i] + fr)
            left[i] = mixWet(left[i], wl, mix)
            right[i] = mixWet(right[i], wr, mix)
        }
    }

    override fun reset() { dl.clear(); dr.clear(); lowCut.reset(); highCut.reset(); smoothDelay = delaySamples }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "tape_delay", name = "Tape Delay", category = PluginCategory.DELAY,
            description = "Warm analog tape echo",
            params = listOf(
                ParamSpec("sync", "Sync", 0f, 6f, 0f, choices = SYNC_CHOICES),
                ParamSpec("time", "Delay Time", 10f, 2000f, 375f, "ms", ParamScale.LOG),
                ParamSpec("feedback", "Feedback", 0f, 95f, 35f, "%"),
                ParamSpec("lowCut", "Low Cut", 20f, 2000f, 200f, "Hz", ParamScale.LOG),
                ParamSpec("highCut", "High Cut", 1000f, 20000f, 5000f, "Hz", ParamScale.LOG),
                ParamSpec("flutterRate", "Flutter Rate", 0.1f, 10f, 0.8f, "Hz", ParamScale.LOG),
                ParamSpec("flutterDepth", "Flutter Depth", 0f, 100f, 15f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 25f, "%"),
            ),
            presets = mapOf(
                "Slapback" to mapOf("time" to 110f, "feedback" to 5f, "mix" to 30f),
                "Dub Echo" to mapOf("time" to 500f, "feedback" to 70f, "highCut" to 2500f, "mix" to 35f),
            ),
            factory = ::TapeDelay,
        )
    }
}

/** Logic "Stereo Delay": independent L/R times with cross-feedback (ping-pong). */
class StereoDelay(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val dl = DelayLine(sr * 2 + 64)
    private val dr = DelayLine(sr * 2 + 64)
    private var tl = 1; private var tr = 1

    private var syncedTempo = 0f

    override fun update() {
        val sync = param("sync")
        tl = (syncedMs(sync, hostTempo, param("leftTime")) * 0.001f * sampleRate).toInt().coerceIn(1, sampleRate * 2)
        tr = (syncedMs(sync, hostTempo, param("rightTime")) * (if (sync > 0.5f) 1.5f else 1f) * 0.001f * sampleRate).toInt().coerceIn(1, sampleRate * 2)
        syncedTempo = hostTempo
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        if (syncedTempo != hostTempo) update()
        val fb = param("feedback") / 100f
        val cross = param("crossfeed") / 100f
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val wl = dl.readInt(tl - 1); val wr = dr.readInt(tr - 1)
            dl.push(left[i] + (wl * (1f - cross) + wr * cross) * fb)
            dr.push(right[i] + (wr * (1f - cross) + wl * cross) * fb)
            left[i] = mixWet(left[i], wl, mix)
            right[i] = mixWet(right[i], wr, mix)
        }
    }

    override fun reset() { dl.clear(); dr.clear() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "stereo_delay", name = "Stereo Delay", category = PluginCategory.DELAY,
            description = "Dual delay with ping-pong crossfeed",
            params = listOf(
                ParamSpec("sync", "Sync", 0f, 6f, 0f, choices = SYNC_CHOICES),
                ParamSpec("leftTime", "Left Time", 1f, 2000f, 250f, "ms", ParamScale.LOG),
                ParamSpec("rightTime", "Right Time", 1f, 2000f, 375f, "ms", ParamScale.LOG),
                ParamSpec("feedback", "Feedback", 0f, 95f, 40f, "%"),
                ParamSpec("crossfeed", "Crossfeed", 0f, 100f, 50f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 25f, "%"),
            ),
            factory = ::StereoDelay,
        )
    }
}

/** Logic "Sample Delay": per-channel sample offsets for phase alignment / Haas tricks. */
class SampleDelay(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val dl = DelayLine(4096)
    private val dr = DelayLine(4096)

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val l = param("left").toInt(); val r = param("right").toInt()
        for (i in offset until offset + frames) {
            dl.push(left[i]); dr.push(right[i])
            left[i] = dl.readInt(l); right[i] = dr.readInt(r)
        }
    }

    override fun reset() { dl.clear(); dr.clear() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "sample_delay", name = "Sample Delay", category = PluginCategory.DELAY,
            description = "Delay each channel by a number of samples",
            params = listOf(
                ParamSpec("left", "Left", 0f, 4000f, 0f, "smp"),
                ParamSpec("right", "Right", 0f, 4000f, 0f, "smp"),
            ),
            factory = ::SampleDelay,
        )
    }
}

/** Note values for tempo-synced delays (index 0 = free time in ms). */
val SYNC_CHOICES = listOf("Free", "1/2", "1/4", "1/8", "1/8 D", "1/8 T", "1/16")

/** Delay time in ms for a sync choice index at [bpm]; [freeMs] when not synced. */
fun syncedMs(choice: Float, bpm: Float, freeMs: Float): Float {
    val beat = 60000f / bpm.coerceAtLeast(20f)
    return when (choice.toInt()) {
        1 -> beat * 2f
        2 -> beat
        3 -> beat / 2f
        4 -> beat * 0.75f
        5 -> beat / 3f
        6 -> beat / 4f
        else -> freeMs
    }.coerceAtMost(1990f)
}
