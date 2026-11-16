package com.musiccut.core.plugin.effects

import com.musiccut.core.dsp.Biquad
import com.musiccut.core.dsp.Convolver
import com.musiccut.core.dsp.DelayLine
import com.musiccut.core.dsp.Fft
import com.musiccut.core.dsp.FilterType
import com.musiccut.core.dsp.PitchDetector
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.ParamScale
import com.musiccut.core.plugin.ParamSpec
import com.musiccut.core.plugin.PluginCategory
import com.musiccut.core.plugin.PluginDescriptor
import com.musiccut.core.plugin.mixWet
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Logic "Space Designer": convolution reverb. Impulse responses are synthesized from the space
 * type, decay, size and damping (a background thread builds the IR, the audio thread swaps it in).
 */
class SpaceDesigner(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    @Volatile private var conv: Convolver? = null
    @Volatile private var building = false
    private var pendingKey = ""
    private var builtKey = ""
    private val lowCut = Biquad()

    override fun update() {
        lowCut.set(FilterType.HIGH_PASS, sampleRate, param("lowCut"), 0.707f)
        val key = listOf("type", "decay", "size", "damping", "width", "predelay").joinToString { "%.3f".format(param(it)) }
        if (key == builtKey || key == pendingKey) return
        pendingKey = key
        val type = param("type").toInt(); val decay = param("decay"); val size = param("size") / 100f
        val damping = param("damping") / 100f; val width = param("width") / 100f; val pre = param("predelay")
        if (!building) {
            building = true
            Thread({
                try {
                    val (l, r) = buildIr(sampleRate, type, decay, size, damping, width, pre)
                    conv = Convolver(l, r, 512)
                    builtKey = key
                } finally {
                    building = false
                }
            }, "IR-builder").apply { isDaemon = true }.start()
        }
    }

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        if (!building && pendingKey != builtKey) update()
        val c = conv ?: return
        val mix = param("mix")
        val out = param("output")
        val g = 10f.pow(out / 20f)
        // process the wet signal in chunks through scratch buffers
        var pos = offset
        while (pos < offset + frames) {
            val n = minOf(512, offset + frames - pos)
            for (i in 0 until n) { wetL[i] = left[pos + i]; wetR[i] = right[pos + i] }
            c.process(wetL, wetR, 0, n)
            for (i in 0 until n) {
                val wl = lowCut.processL(wetL[i]) * g
                val wr = lowCut.processR(wetR[i]) * g
                left[pos + i] = mixWet(left[pos + i], wl, mix)
                right[pos + i] = mixWet(right[pos + i], wr, mix)
            }
            pos += n
        }
    }

    private val wetL = FloatArray(512)
    private val wetR = FloatArray(512)

    override fun reset() { conv?.reset(); lowCut.reset() }

    companion object {
        val TYPES = listOf("Room", "Hall", "Plate", "Chamber", "Cathedral", "Spring")

        /** Synthesizes a stereo impulse response. */
        fun buildIr(sr: Int, type: Int, decay: Float, size: Float, damping: Float, width: Float, predelayMs: Float): Pair<FloatArray, FloatArray> {
            val len = (sr * (decay * 1.3f).coerceIn(0.2f, 6f)).toInt()
            val pre = (predelayMs * 0.001f * sr).toInt()
            val rnd = Random(1234 + type)
            val l = FloatArray(len + pre)
            val r = FloatArray(len + pre)
            val density = when (type) { 2 -> 1.0f; 5 -> 0.5f; 0 -> 0.8f; else -> 0.9f }
            val bright = when (type) { 2 -> 0.9f; 5 -> 0.7f; 4 -> 0.35f; 0 -> 0.55f; else -> 0.5f }
            // time-varying one-pole lowpass: HF decays faster (damping)
            var lpL = 0f; var lpR = 0f
            for (i in 0 until len) {
                val t = i.toFloat() / sr
                val env = exp(-6.91f * t / decay)
                val cutoff = (bright * (1f - damping * (t / decay).coerceAtMost(1f))).coerceIn(0.03f, 0.98f)
                val nl = if (rnd.nextFloat() < density) (rnd.nextFloat() * 2 - 1) else 0f
                val nrRaw = if (rnd.nextFloat() < density) (rnd.nextFloat() * 2 - 1) else 0f
                val nr = nl * (1 - width) + nrRaw * width
                lpL += cutoff * (nl - lpL)
                lpR += cutoff * (nr - lpR)
                var sl = lpL * env
                var sr2 = lpR * env
                if (type == 5) { // spring: add metallic chirp resonance
                    val ch = sin(2 * PI * (900 + 600 * sin(2 * PI * 7 * t)) * t).toFloat() * 0.3f * env
                    sl += ch; sr2 += ch
                }
                l[pre + i] = sl
                r[pre + i] = sr2
            }
            // early reflections scale with room size
            val taps = 8
            for (k in 1..taps) {
                val d = (sr * (0.004f + size * 0.07f) * k / taps).toInt() + pre
                val a = 0.6f * (1f - k / (taps + 1f))
                if (d < l.size) { l[d] += a * (if (k % 2 == 0) 1f else -1f); r[(d * 1.07f).toInt().coerceAtMost(r.size - 1)] += a }
            }
            // normalize energy
            var e = 0.0
            for (i in l.indices) e += l[i] * l[i] + r[i] * r[i]
            val norm = (1.0 / sqrt(e / 2 + 1e-9)).toFloat() * 0.35f
            for (i in l.indices) { l[i] *= norm; r[i] *= norm }
            return l to r
        }

        val DESCRIPTOR = PluginDescriptor(
            id = "space_designer", name = "Space Designer", category = PluginCategory.REVERB,
            description = "Convolution reverb with synthesized impulse responses",
            params = listOf(
                ParamSpec("type", "Space", 0f, 5f, 1f, choices = TYPES),
                ParamSpec("decay", "Length", 0.2f, 6f, 2.2f, "s", ParamScale.LOG),
                ParamSpec("size", "Size", 0f, 100f, 50f, "%"),
                ParamSpec("damping", "Damping", 0f, 100f, 45f, "%"),
                ParamSpec("predelay", "Pre-Delay", 0f, 150f, 15f, "ms"),
                ParamSpec("width", "Spread", 0f, 100f, 90f, "%"),
                ParamSpec("lowCut", "Low Cut", 20f, 1000f, 120f, "Hz", ParamScale.LOG),
                ParamSpec("output", "Output", -24f, 6f, 0f, "dB"),
                ParamSpec("mix", "Mix", 0f, 100f, 25f, "%"),
            ),
            presets = mapOf(
                "Vocal Room" to mapOf("type" to 0f, "decay" to 0.8f, "size" to 30f, "mix" to 20f),
                "Concert Hall" to mapOf("type" to 1f, "decay" to 2.8f, "size" to 80f, "predelay" to 30f, "mix" to 28f),
                "Bright Plate" to mapOf("type" to 2f, "decay" to 1.8f, "damping" to 20f, "mix" to 25f),
                "Cathedral" to mapOf("type" to 4f, "decay" to 5.5f, "size" to 100f, "predelay" to 60f, "mix" to 35f),
                "Spring Tank" to mapOf("type" to 5f, "decay" to 2f, "mix" to 30f),
            ),
            factory = ::SpaceDesigner,
        )
    }
}

private val KEYS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Shared variable-ratio granular pitch shifter (two crossfaded read heads). */
private class GranularShifter(sr: Int, windowMs: Float = 40f) {
    val window = sr * windowMs / 1000f
    private val dl = DelayLine((window * 2).toInt() + 16)
    private val dr = DelayLine((window * 2).toInt() + 16)
    private var phase = 0f

    fun process(l: Float, r: Float, ratio: Float, out: FloatArray) {
        dl.push(l); dr.push(r)
        phase += (1f - ratio) / window
        if (phase >= 1f) phase -= 1f
        if (phase < 0f) phase += 1f
        val p2 = (phase + 0.5f) % 1f
        val d1 = 2f + phase * window
        val d2 = 2f + p2 * window
        val g1 = sin(PI * phase).toFloat().let { it * it }
        val g2 = sin(PI * p2).toFloat().let { it * it }
        out[0] = dl.read(d1) * g1 + dl.read(d2) * g2
        out[1] = dr.read(d1) * g1 + dr.read(d2) * g2
    }

    fun reset() { dl.clear(); dr.clear(); phase = 0f }
}

/** Logic "Pitch Correction": real-time pitch tracking and snapping to a key / scale (auto-tune). */
class PitchCorrection(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val detector = PitchDetector(sr, 1024, 0.2f)
    private val ring = FloatArray(1024)
    private var ringPos = 0
    private var counter = 0
    private var targetRatio = 1f
    private var ratio = 1f
    private val shifter = GranularShifter(sr, 30f)
    private val tmp = FloatArray(2)
    private val analysis = FloatArray(1024)
    @Volatile private var detectedMidi = 0f
    @Volatile private var correctionCents = 0f

    override val readouts: Map<String, Float> get() = mapOf("midi" to detectedMidi, "cents" to correctionCents)

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val key = param("key").toInt()
        val scale = PitchDetector.SCALES.values.elementAt(param("scale").toInt().coerceIn(0, PitchDetector.SCALES.size - 1))
        val responseCoef = exp(-1.0 / (param("response").coerceAtLeast(1f) * 0.001 * sampleRate)).toFloat()
        val amount = param("amount") / 100f
        val detune = param("detune") / 100f
        for (i in offset until offset + frames) {
            ring[ringPos] = (left[i] + right[i]) * 0.5f
            ringPos = (ringPos + 1) and 1023
            if (++counter >= 256) {
                counter = 0
                for (k in 0 until 1024) analysis[k] = ring[(ringPos + k) and 1023]
                val hz = detector.detect(analysis, 0, 70f, 1000f)
                targetRatio = if (hz > 0) {
                    val midi = PitchDetector.hzToMidi(hz)
                    val target = PitchDetector.snap(midi, key, scale) + detune
                    detectedMidi = midi
                    val corr = (target - midi) * amount
                    correctionCents = corr * 100f
                    2f.pow(corr / 12f)
                } else 1f
            }
            ratio = targetRatio + (ratio - targetRatio) * responseCoef
            shifter.process(left[i], right[i], ratio, tmp)
            left[i] = tmp[0]; right[i] = tmp[1]
        }
    }

    override fun reset() { shifter.reset(); ring.fill(0f); ratio = 1f; targetRatio = 1f }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "pitch_correction", name = "Pitch Correction", category = PluginCategory.PITCH,
            description = "Auto-tune: snaps vocals to the notes of a key and scale",
            params = listOf(
                ParamSpec("key", "Root", 0f, 11f, 0f, choices = KEYS),
                ParamSpec("scale", "Scale", 0f, (PitchDetector.SCALES.size - 1).toFloat(), 1f, choices = PitchDetector.SCALES.keys.toList()),
                ParamSpec("response", "Response", 1f, 400f, 40f, "ms", ParamScale.LOG),
                ParamSpec("amount", "Amount", 0f, 100f, 100f, "%"),
                ParamSpec("detune", "Detune", -50f, 50f, 0f, "ct"),
            ),
            presets = mapOf(
                "Natural" to mapOf("response" to 80f, "amount" to 70f),
                "Hard Tune (T-Pain)" to mapOf("response" to 1f, "amount" to 100f),
            ),
            factory = ::PitchCorrection,
        )
    }
}

/**
 * Logic "Vocal Transformer": independent pitch and formant shifting in the frequency domain
 * (phase-vocoder bin remapping with cepstral-style spectral envelope preservation).
 */
class VocalTransformer(sr: Int) : AudioPlugin(sr) {
    override val descriptor get() = DESCRIPTOR
    private val n = 1024
    private val osamp = 4
    private val hop = n / osamp
    private val fft = Fft(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val inFifo = Array(2) { FloatArray(n) }
    private val outFifo = Array(2) { FloatArray(n) }
    private val accum = Array(2) { FloatArray(2 * n) }
    private val lastPhase = Array(2) { FloatArray(n / 2 + 1) }
    private val sumPhase = Array(2) { FloatArray(n / 2 + 1) }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val mag = FloatArray(n / 2 + 1)
    private val freq = FloatArray(n / 2 + 1)
    private val env = FloatArray(n / 2 + 1)
    private val synMag = FloatArray(n / 2 + 1)
    private val synFreq = FloatArray(n / 2 + 1)
    private val prefix = FloatArray(n / 2 + 2)
    private var rover = n - hop

    override val latency: Int get() = n

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        val pitch = 2f.pow((param("pitch") + param("fine") / 100f) / 12f)
        val formant = 2f.pow(param("formant") / 12f)
        val mix = param("mix")
        for (i in offset until offset + frames) {
            inFifo[0][rover] = left[i]
            inFifo[1][rover] = right[i]
            val ol = outFifo[0][rover - (n - hop)]
            val orr = outFifo[1][rover - (n - hop)]
            left[i] = mixWet(left[i], ol, mix)
            right[i] = mixWet(right[i], orr, mix)
            rover++
            if (rover >= n) {
                rover = n - hop
                for (c in 0..1) frame(c, pitch, formant)
            }
        }
    }

    private fun frame(c: Int, pitch: Float, formant: Float) {
        val half = n / 2
        val expct = 2.0 * PI * hop / n
        val binHz = sampleRate.toDouble() / n
        for (k in 0 until n) { re[k] = inFifo[c][k] * window[k]; im[k] = 0f }
        fft.forward(re, im)
        for (k in 0..half) {
            val m = 2f * sqrt(re[k] * re[k] + im[k] * im[k])
            val p = atan2(im[k], re[k])
            var tmp = (p - lastPhase[c][k]).toDouble()
            lastPhase[c][k] = p
            tmp -= k * expct
            var qpd = (tmp / PI).toInt()
            qpd += if (qpd >= 0) qpd and 1 else -(qpd and 1)
            tmp -= PI * qpd
            tmp = osamp * tmp / (2.0 * PI)
            freq[k] = ((k + tmp) * binHz).toFloat()
            mag[k] = m
        }
        // spectral envelope: log-magnitude smoothed over ~ 1/3 octave of bins
        prefix[0] = 0f
        for (k in 0..half) prefix[k + 1] = prefix[k] + ln(mag[k] + 1e-9f)
        for (k in 0..half) {
            val w = maxOf(2, k / 8)
            val a = maxOf(0, k - w)
            val b = minOf(half, k + w)
            env[k] = exp((prefix[b + 1] - prefix[a]) / (b - a + 1))
        }
        synMag.fill(0f); synFreq.fill(0f)
        for (k in 0..half) {
            val idx = (k * pitch).toInt()
            if (idx <= half) {
                val flat = mag[k] / (env[k] + 1e-9f)
                val envIdx = (idx / formant).toInt().coerceIn(0, half)
                synMag[idx] += flat * env[envIdx]
                synFreq[idx] = freq[k] * pitch
            }
        }
        for (k in 0..half) {
            var tmp = synFreq[k].toDouble()
            tmp -= k * binHz
            tmp /= binHz
            tmp = 2.0 * PI * tmp / osamp
            tmp += k * expct
            sumPhase[c][k] += tmp.toFloat()
            val ph = sumPhase[c][k]
            re[k] = synMag[k] * cos(ph)
            im[k] = synMag[k] * sin(ph)
        }
        for (k in 1 until half) { re[n - k] = re[k]; im[n - k] = -im[k] }
        im[0] = 0f; im[half] = 0f
        fft.inverse(re, im)
        val acc = accum[c]
        // Hann² overlap-add at 4x overlap sums to 1.5, hence 4/3 instead of 2
        for (k in 0 until n) acc[k] += (4f / 3f) * window[k] * re[k] / osamp
        System.arraycopy(acc, 0, outFifo[c], 0, hop)
        System.arraycopy(acc, hop, acc, 0, n)
        java.util.Arrays.fill(acc, n, 2 * n, 0f)
        System.arraycopy(inFifo[c], hop, inFifo[c], 0, n - hop)
    }

    override fun reset() {
        for (c in 0..1) { inFifo[c].fill(0f); outFifo[c].fill(0f); accum[c].fill(0f); lastPhase[c].fill(0f); sumPhase[c].fill(0f) }
        rover = n - hop
    }

    companion object {
        val DESCRIPTOR = PluginDescriptor(
            id = "vocal_transformer", name = "Vocal Transformer", category = PluginCategory.PITCH,
            description = "Pitch and formant shifting (robot, chipmunk, deep voice)",
            params = listOf(
                ParamSpec("pitch", "Pitch", -12f, 12f, 0f, "st"),
                ParamSpec("fine", "Fine", -100f, 100f, 0f, "ct"),
                ParamSpec("formant", "Formant", -12f, 12f, 0f, "st"),
                ParamSpec("mix", "Mix", 0f, 100f, 100f, "%"),
            ),
            presets = mapOf(
                "Deep Voice" to mapOf("pitch" to -5f, "formant" to -3f),
                "Chipmunk" to mapOf("pitch" to 7f, "formant" to 6f),
                "Gender Up (formant)" to mapOf("formant" to 3f),
                "Gender Down (formant)" to mapOf("formant" to -3f),
            ),
            factory = ::VocalTransformer,
        )
    }
}
