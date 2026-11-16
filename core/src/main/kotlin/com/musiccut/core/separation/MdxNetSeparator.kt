package com.musiccut.core.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.Resampler
import com.musiccut.core.dsp.Dft
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos

/** Parameters of an MDX-Net (UVR) ONNX model. */
data class MdxModelSpec(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val nFft: Int = 7680,
    val hop: Int = 1024,
    val dimF: Int = 3072,
    val dimT: Int = 256,
    val sampleRate: Int = 44100,
    /** Level compensation applied to the model's (primary) output stem. */
    val compensate: Float = 1f,
    val approxSizeMb: Int = 64,
) {
    companion object {
        private const val BASE = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/"
        val VOC_FT = MdxModelSpec("voc_ft", "UVR-MDX-NET Voc FT (보컬, 최고 품질)", "UVR-MDX-NET-Voc_FT.onnx", BASE + "UVR-MDX-NET-Voc_FT.onnx")
        val KIM_VOCAL_2 = MdxModelSpec("kim_vocal_2", "Kim Vocal 2 (보컬)", "Kim_Vocal_2.onnx", BASE + "Kim_Vocal_2.onnx")
        val ALL = listOf(VOC_FT, KIM_VOCAL_2)
    }
}

/**
 * AI vocal / instrumental separation with an MDX-Net ONNX model (as used by Ultimate Vocal
 * Remover). The mix is cut into overlapping chunks, each chunk is turned into a stereo complex
 * spectrogram (STFT, centered, periodic Hann), the network predicts the vocal spectrogram, which is
 * transformed back and cross-faded; instrumental = mix − vocals.
 */
class MdxNetSeparator(
    private val modelFile: File,
    private val spec: MdxModelSpec,
    private val threads: Int = 4,
    private val sessionOptions: (OrtSession.SessionOptions) -> Unit = {},
) : StemSeparator {
    override val name = "AI (${spec.title})"

    override fun separate(
        input: AudioBuffer,
        options: SeparationOptions,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Stems {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            sessionOptions(this)
        }
        env.createSession(modelFile.absolutePath, opts).use { session ->
            val mix = if (input.sampleRate != spec.sampleRate) Resampler.resample(input, spec.sampleRate) else input
            val vocals = separateVocals(env, session, mix, progress, isCancelled)
            val v = if (input.sampleRate != spec.sampleRate) Resampler.resample(vocals, input.sampleRate) else vocals
            val n = minOf(v.frames, input.frames)
            val vl = v.left.copyOf(input.frames)
            val vr = v.right.copyOf(input.frames)
            for (i in n until input.frames) { vl[i] = 0f; vr[i] = 0f }
            val il = FloatArray(input.frames) { input.left[it] - vl[it] }
            val ir = FloatArray(input.frames) { input.right[it] - vr[it] }
            progress(1f)
            return Stems(AudioBuffer(vl, vr, input.sampleRate), AudioBuffer(il, ir, input.sampleRate))
        }
    }

    /** Runs the network over the whole mix; returns the (compensated) vocal stem. */
    fun separateVocals(env: OrtEnvironment, session: OrtSession, mix: AudioBuffer, progress: (Float) -> Unit, isCancelled: () -> Boolean): AudioBuffer {
        val chunk = spec.hop * (spec.dimT - 1)
        val overlap = chunk / 4
        val step = chunk - overlap
        val len = mix.frames
        val padFront = overlap
        val total = padFront + len + chunk
        val padded = Array(2) { FloatArray(total) }
        mix.left.copyInto(padded[0], padFront)
        mix.right.copyInto(padded[1], padFront)
        val result = Array(2) { FloatArray(total) }
        val divider = FloatArray(total)
        val window = FloatArray(chunk) { i ->
            when {
                i < overlap -> i.toFloat() / overlap
                i >= chunk - overlap -> (chunk - i).toFloat() / overlap
                else -> 1f
            }.coerceAtLeast(1e-3f)
        }
        val stft = MdxStft(spec)
        val starts = ArrayList<Int>()
        var s = 0
        while (s < padFront + len) { starts += s; s += step }
        val inputName = session.inputNames.first()
        starts.forEachIndexed { ci, start ->
            if (isCancelled()) throw InterruptedException("cancelled")
            val l = FloatArray(chunk) { padded[0].getOrElse(start + it) { 0f } }
            val r = FloatArray(chunk) { padded[1].getOrElse(start + it) { 0f } }
            val spek = stft.forward(l, r)
            val shape = longArrayOf(1, 4, spec.dimF.toLong(), spec.dimT.toLong())
            val outSpec = OnnxTensor.createTensor(env, FloatBuffer.wrap(spek), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { res ->
                    val out = res[0] as OnnxTensor
                    val fb = out.floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
            val (ol, or) = stft.inverse(outSpec, chunk)
            for (i in 0 until chunk) {
                val idx = start + i
                if (idx >= total) break
                result[0][idx] += ol[i] * window[i]
                result[1][idx] += or[i] * window[i]
                divider[idx] += window[i]
            }
            progress((ci + 1f) / starts.size * 0.98f)
        }
        val c = spec.compensate
        val vl = FloatArray(len) { val i = it + padFront; if (divider[i] > 0) result[0][i] / divider[i] * c else 0f }
        val vr = FloatArray(len) { val i = it + padFront; if (divider[i] > 0) result[1][i] / divider[i] * c else 0f }
        return AudioBuffer(vl, vr, mix.sampleRate)
    }
}

/** torch.stft / istft equivalent (center=True, reflect padding, periodic Hann) in MDX tensor layout. */
class MdxStft(private val spec: MdxModelSpec) {
    private val n = spec.nFft
    private val hop = spec.hop
    private val bins = n / 2 + 1
    private val dft = Dft(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    private fun reflect(x: FloatArray, i: Int): Float {
        var j = i
        val len = x.size
        if (len == 1) return x[0]
        while (j < 0 || j >= len) {
            if (j < 0) j = -j
            if (j >= len) j = 2 * (len - 1) - j
        }
        return x[j]
    }

    /** Returns a [4, dimF, dimT] tensor: L re, L im, R re, R im. Bins 0..2 are zeroed (as UVR does). */
    fun forward(l: FloatArray, r: FloatArray): FloatArray {
        val dimF = spec.dimF
        val dimT = spec.dimT
        val out = FloatArray(4 * dimF * dimT)
        val half = n / 2
        for (t in 0 until dimT) {
            val start = t * hop - half
            for (i in 0 until n) {
                val w = window[i]
                re[i] = reflect(l, start + i) * w
                im[i] = reflect(r, start + i) * w
            }
            dft.forward(re, im)
            for (k in 3 until dimF) {
                val nk = if (k == 0) 0 else n - k
                val lr = (re[k] + re[nk]) * 0.5f
                val li = (im[k] - im[nk]) * 0.5f
                val rr = (im[k] + im[nk]) * 0.5f
                val ri = -(re[k] - re[nk]) * 0.5f
                out[(0 * dimF + k) * dimT + t] = lr
                out[(1 * dimF + k) * dimT + t] = li
                out[(2 * dimF + k) * dimT + t] = rr
                out[(3 * dimF + k) * dimT + t] = ri
            }
        }
        return out
    }

    /** Inverse of [forward] for a [4, dimF, dimT] tensor; returns [length] samples per channel. */
    fun inverse(spec4: FloatArray, length: Int): Pair<FloatArray, FloatArray> {
        val dimF = spec.dimF
        val dimT = spec.dimT
        val half = n / 2
        val total = length + n
        val ol = FloatArray(total)
        val or = FloatArray(total)
        val norm = FloatArray(total)
        for (t in 0 until dimT) {
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (k in 0 until minOf(dimF, bins)) {
                val lr = spec4[(0 * dimF + k) * dimT + t]
                val li = spec4[(1 * dimF + k) * dimT + t]
                val rr = spec4[(2 * dimF + k) * dimT + t]
                val ri = spec4[(3 * dimF + k) * dimT + t]
                if (k == 0 || k == half) {
                    re[k] = lr
                    im[k] = rr
                } else {
                    // Z = L + iR with Hermitian symmetry of L and R
                    re[k] = lr - ri; im[k] = li + rr
                    re[n - k] = lr + ri; im[n - k] = -li + rr
                }
            }
            dft.inverse(re, im)
            val pos = t * hop // position in the center-padded signal
            for (i in 0 until n) {
                val idx = pos + i
                if (idx >= total) break
                val w = window[i]
                ol[idx] += re[i] * w
                or[idx] += im[i] * w
                norm[idx] += w * w
            }
        }
        val l = FloatArray(length) { val i = it + half; if (norm[i] > 1e-8f) ol[i] / norm[i] else 0f }
        val r = FloatArray(length) { val i = it + half; if (norm[i] > 1e-8f) or[i] / norm[i] else 0f }
        return l to r
    }
}
