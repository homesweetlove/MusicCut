package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.Biquad
import com.musiccut.core.dsp.FilterType
import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.ParamScale
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.mixWet
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.tanh

/** Logic "Overdrive": smooth tube-like soft clipping. */
class Overdrive(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val tone = Biquad()
    private var drive = 1f
    private var out = 1f

    override fun update() {
        drive = dbToGain(param("drive"))
        out = dbToGain(param("output"))
        tone.set(FilterType.LOW_PASS, sampleRate, param("tone"), 0.707f)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val norm = 1f / tanh(drive).coerceAtLeast(0.1f)
        for (i in offset until offset + frames) {
            left[i] = tone.processL(tanh(left[i] * drive) * norm) * out
            right[i] = tone.processR(tanh(right[i] * drive) * norm) * out
        }
    }

    override fun reset() = tone.reset()

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "overdrive", name = "Overdrive", category = PluginCategory.DISTORTION,
            description = "Warm tube-style saturation",
            params = listOf(
                ParamSpec("drive", "Drive", 0f, 40f, 12f, "dB"),
                ParamSpec("tone", "Tone", 500f, 20000f, 6000f, "Hz", ParamScale.LOG),
                ParamSpec("output", "Output", -30f, 6f, -6f, "dB"),
            ),
            factory = ::Overdrive,
        )
    }
}

/** Logic "Distortion": asymmetric hard clipping with pre-emphasis. */
class Distortion(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val pre = Biquad()
    private val tone = Biquad()
    private var drive = 1f
    private var out = 1f

    override fun update() {
        drive = dbToGain(param("drive"))
        out = dbToGain(param("output"))
        pre.set(FilterType.HIGH_PASS, sampleRate, 120f, 0.707f)
        tone.set(FilterType.LOW_PASS, sampleRate, param("tone"), 0.9f)
    }

    private fun clip(x: Float): Float = when {
        x > 0.8f -> 0.8f + (x - 0.8f) / (1f + ((x - 0.8f) / 0.2f).pow(2))
        x < -0.6f -> -0.6f + (x + 0.6f) / (1f + ((x + 0.6f) / 0.4f).pow(2))
        else -> x
    }.coerceIn(-1f, 1f)

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val mix = param("mix")
        for (i in offset until offset + frames) {
            val l = left[i]; val r = right[i]
            val wl = tone.processL(clip(pre.processL(l) * drive)) * out
            val wr = tone.processR(clip(pre.processR(r) * drive)) * out
            left[i] = mixWet(l, wl, mix); right[i] = mixWet(r, wr, mix)
        }
    }

    override fun reset() { pre.reset(); tone.reset() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "distortion", name = "Distortion", category = PluginCategory.DISTORTION,
            description = "Aggressive fuzz / distortion",
            params = listOf(
                ParamSpec("drive", "Drive", 0f, 50f, 24f, "dB"),
                ParamSpec("tone", "Tone", 500f, 16000f, 4000f, "Hz", ParamScale.LOG),
                ParamSpec("output", "Output", -40f, 6f, -12f, "dB"),
                ParamSpec("mix", "Mix", 0f, 100f, 100f, "%"),
            ),
            factory = ::Distortion,
        )
    }
}

/** Logic "Bitcrusher": bit depth and sample-rate reduction. */
class Bitcrusher(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private var holdL = 0f; private var holdR = 0f
    private var counter = 0f

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val levels = 2f.pow(param("bits") - 1f)
        val step = param("downsample")
        val drive = dbToGain(param("drive"))
        val mix = param("mix")
        for (i in offset until offset + frames) {
            counter += 1f
            if (counter >= step) {
                counter -= step
                holdL = round(tanh(left[i] * drive) * levels) / levels
                holdR = round(tanh(right[i] * drive) * levels) / levels
            }
            left[i] = mixWet(left[i], holdL, mix)
            right[i] = mixWet(right[i], holdR, mix)
        }
    }

    override fun reset() { holdL = 0f; holdR = 0f; counter = 0f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "bitcrusher", name = "Bitcrusher", category = PluginCategory.DISTORTION,
            description = "Lo-fi digital degradation",
            params = listOf(
                ParamSpec("bits", "Resolution", 1f, 24f, 8f, "bit"),
                ParamSpec("downsample", "Downsampling", 1f, 50f, 4f, "x", ParamScale.LOG),
                ParamSpec("drive", "Drive", 0f, 24f, 0f, "dB"),
                ParamSpec("mix", "Mix", 0f, 100f, 100f, "%"),
            ),
            factory = ::Bitcrusher,
        )
    }
}

/** Logic "Exciter": adds generated upper harmonics for brilliance. */
class Exciter(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val hp = Biquad()
    private val hp2 = Biquad()

    override fun update() {
        hp.set(FilterType.HIGH_PASS, sampleRate, param("frequency"), 0.707f)
        hp2.set(FilterType.HIGH_PASS, sampleRate, param("frequency"), 0.707f)
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val amt = param("harmonics") / 100f
        val drive = 1f + amt * 10f
        val mix = param("mix") / 100f
        for (i in offset until offset + frames) {
            val hl = hp.processL(left[i]); val hr = hp.processR(right[i])
            val gl = hp2.processL(tanh(hl * drive) - hl * 0.5f * abs(hl))
            val gr = hp2.processR(tanh(hr * drive) - hr * 0.5f * abs(hr))
            left[i] += gl * mix
            right[i] += gr * mix
        }
    }

    override fun reset() { hp.reset(); hp2.reset() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "exciter", name = "Exciter", category = PluginCategory.DISTORTION,
            description = "Harmonic enhancer for air and presence",
            params = listOf(
                ParamSpec("frequency", "Frequency", 1000f, 12000f, 4500f, "Hz", ParamScale.LOG),
                ParamSpec("harmonics", "Harmonics", 0f, 100f, 40f, "%"),
                ParamSpec("mix", "Mix", 0f, 100f, 25f, "%"),
            ),
            factory = ::Exciter,
        )
    }
}
