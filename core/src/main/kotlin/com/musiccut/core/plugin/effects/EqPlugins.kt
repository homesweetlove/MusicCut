package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.Biquad
import com.musiccut.core.dsp.FilterType
import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.ParamScale
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor

private val ON_OFF = listOf("Off", "On")

/** Logic "Channel EQ": HPF, low shelf, 4 parametric bells, high shelf, LPF. */
class ChannelEq(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val tap = com.musiccut.core.plugin.AnalyzerTap()
    override val analyzer get() = tap
    private val hp = Biquad()
    private val lp = Biquad()
    private val lowShelf = Biquad()
    private val highShelf = Biquad()
    private val bells = Array(4) { Biquad() }
    private var hpOn = false
    private var lpOn = false
    private var out = 1f

    override fun update() {
        hpOn = param("hpOn") > 0.5f
        lpOn = param("lpOn") > 0.5f
        hp.set(FilterType.HIGH_PASS, sampleRate, param("hpFreq"), 0.707f)
        lp.set(FilterType.LOW_PASS, sampleRate, param("lpFreq"), 0.707f)
        lowShelf.set(FilterType.LOW_SHELF, sampleRate, param("lsFreq"), 0.707f, param("lsGain"))
        highShelf.set(FilterType.HIGH_SHELF, sampleRate, param("hsFreq"), 0.707f, param("hsGain"))
        for (b in 0 until 4) {
            bells[b].set(FilterType.PEAK, sampleRate, param("b${b + 1}Freq"), param("b${b + 1}Q"), param("b${b + 1}Gain"))
        }
        out = dbToGain(param("output"))
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        if (hpOn) hp.process(left, right, offset, frames)
        lowShelf.process(left, right, offset, frames)
        for (b in bells) b.process(left, right, offset, frames)
        highShelf.process(left, right, offset, frames)
        if (lpOn) lp.process(left, right, offset, frames)
        if (out != 1f) for (i in offset until offset + frames) {
            left[i] *= out; right[i] *= out
        }
        tap.push(left, right, offset, frames)
    }

    override fun reset() {
        hp.reset(); lp.reset(); lowShelf.reset(); highShelf.reset(); bells.forEach { it.reset() }
    }

    companion object {
        private val bellDefaults = listOf(250f, 800f, 2500f, 6000f)
        val DESCRIPTOR = PluginDescriptor(
            id = "channel_eq",
            name = "Channel EQ",
            category = PluginCategory.EQ,
            description = "8-band parametric EQ with high/low cut",
            params = buildList {
                add(ParamSpec("hpOn", "Low Cut", 0f, 1f, 0f, choices = ON_OFF))
                add(ParamSpec("hpFreq", "Low Cut Freq", 20f, 1000f, 80f, "Hz", ParamScale.LOG))
                add(ParamSpec("lsFreq", "Low Shelf Freq", 30f, 1000f, 120f, "Hz", ParamScale.LOG))
                add(ParamSpec("lsGain", "Low Shelf Gain", -24f, 24f, 0f, "dB"))
                bellDefaults.forEachIndexed { i, f ->
                    val n = i + 1
                    add(ParamSpec("b${n}Freq", "Band $n Freq", 30f, 18000f, f, "Hz", ParamScale.LOG))
                    add(ParamSpec("b${n}Gain", "Band $n Gain", -24f, 24f, 0f, "dB"))
                    add(ParamSpec("b${n}Q", "Band $n Q", 0.1f, 12f, 1f, "", ParamScale.LOG))
                }
                add(ParamSpec("hsFreq", "High Shelf Freq", 1000f, 18000f, 8000f, "Hz", ParamScale.LOG))
                add(ParamSpec("hsGain", "High Shelf Gain", -24f, 24f, 0f, "dB"))
                add(ParamSpec("lpOn", "High Cut", 0f, 1f, 0f, choices = ON_OFF))
                add(ParamSpec("lpFreq", "High Cut Freq", 1000f, 20000f, 18000f, "Hz", ParamScale.LOG))
                add(ParamSpec("output", "Output", -24f, 24f, 0f, "dB"))
            },
            presets = mapOf(
                "Vocal Presence" to mapOf("hpOn" to 1f, "hpFreq" to 90f, "b2Freq" to 350f, "b2Gain" to -3f, "b3Freq" to 3000f, "b3Gain" to 3f, "hsFreq" to 10000f, "hsGain" to 2.5f),
                "Warm Bass" to mapOf("lsFreq" to 90f, "lsGain" to 3f, "b2Freq" to 700f, "b2Gain" to -2f, "lpOn" to 1f, "lpFreq" to 8000f),
                "Telephone" to mapOf("hpOn" to 1f, "hpFreq" to 400f, "lpOn" to 1f, "lpFreq" to 3200f, "b3Freq" to 1500f, "b3Gain" to 6f),
                "Air" to mapOf("hsFreq" to 12000f, "hsGain" to 4f),
            ),
            factory = ::ChannelEq,
        )
    }
}

/** Simple 3-band EQ (like Logic's "Fat EQ"/"Silver EQ" lite). */
class ThreeBandEq(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val low = Biquad()
    private val mid = Biquad()
    private val high = Biquad()

    override fun update() {
        low.set(FilterType.LOW_SHELF, sampleRate, param("lowFreq"), 0.707f, param("low"))
        mid.set(FilterType.PEAK, sampleRate, param("midFreq"), 0.9f, param("mid"))
        high.set(FilterType.HIGH_SHELF, sampleRate, param("highFreq"), 0.707f, param("high"))
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        low.process(left, right, offset, frames)
        mid.process(left, right, offset, frames)
        high.process(left, right, offset, frames)
    }

    override fun reset() { low.reset(); mid.reset(); high.reset() }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "three_band_eq", name = "Silver EQ", category = PluginCategory.EQ,
            description = "Quick 3-band tone shaping",
            params = listOf(
                ParamSpec("low", "Low", -18f, 18f, 0f, "dB"),
                ParamSpec("lowFreq", "Low Freq", 40f, 500f, 120f, "Hz", ParamScale.LOG),
                ParamSpec("mid", "Mid", -18f, 18f, 0f, "dB"),
                ParamSpec("midFreq", "Mid Freq", 200f, 8000f, 1000f, "Hz", ParamScale.LOG),
                ParamSpec("high", "High", -18f, 18f, 0f, "dB"),
                ParamSpec("highFreq", "High Freq", 2000f, 16000f, 6000f, "Hz", ParamScale.LOG),
            ),
            factory = ::ThreeBandEq,
        )
    }
}
