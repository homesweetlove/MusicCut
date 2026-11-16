package com.musiccut.core.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.Resampler
import java.io.File
import java.nio.FloatBuffer

/** Published HT-Demucs 6-stem ONNX model metadata. */
data class HtDemucsModelSpec(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val approxSizeMb: Int = 136,
    val sampleRate: Int = 44_100,
    val segmentFrames: Int = 343_980,
    val overlapFrames: Int = segmentFrames / 4,
    val stems: List<StemKind> = listOf(
        StemKind.DRUMS,
        StemKind.BASS,
        StemKind.OTHER,
        StemKind.VOCALS,
        StemKind.GUITAR,
        StemKind.PIANO,
    ),
) {
    companion object {
        val SIX_STEM_FP16 = HtDemucsModelSpec(
            id = "htdemucs_6s_fp16",
            title = "HT-Demucs 6-stem (Vocals · Drums · Bass · Guitar · Piano · Other)",
            fileName = "htdemucs_6s_fp16weights.onnx",
            url = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx?download=true",
            sha256 = "7ce55792e2231c93fbf92de95f5fd5b3a5e6c89f7db690dfd693e8f1dce56869",
        )
    }
}

/**
 * CPU HT-Demucs inference for Android/JVM using ONNX Runtime.
 *
 * The model takes stereo 44.1 kHz chunks of 343,980 samples and returns six stereo stems in the
 * published order drums, bass, other, vocals, guitar, piano. Longer audio is processed with 25%
 * overlap and the same linear overlap-add window as the reference implementation.
 */
class HtDemucsSeparator(
    private val modelFile: File,
    private val spec: HtDemucsModelSpec = HtDemucsModelSpec.SIX_STEM_FP16,
    private val threads: Int = 4,
    private val sessionOptions: (OrtSession.SessionOptions) -> Unit = {},
) : MultiStemSeparator {
    override val name: String = "AI (${spec.title})"

    override fun separate(
        input: AudioBuffer,
        requested: Set<StemKind>,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): MultiStems {
        if (requested.isEmpty()) return MultiStems(emptyMap())
        val wanted = requested intersect spec.stems.toSet()
        require(wanted.isNotEmpty()) { "Requested stems are not provided by ${spec.title}" }

        val originalRate = input.sampleRate
        val mix = if (originalRate == spec.sampleRate) input else Resampler.resample(input, spec.sampleRate)
        val len = mix.frames
        if (len == 0) return MultiStems(wanted.associateWith { AudioBuffer(FloatArray(0), FloatArray(0), originalRate) })

        val chunk = spec.segmentFrames
        val overlap = spec.overlapFrames
        val stride = chunk - overlap
        val chunks = maxOf(1, (len + stride - 1) / stride)
        val window = FloatArray(chunk) { i ->
            when {
                i < overlap -> i.toFloat() / maxOf(1, overlap - 1)
                i >= chunk - overlap -> (chunk - 1 - i).toFloat() / maxOf(1, overlap - 1)
                else -> 1f
            }.coerceIn(0f, 1f)
        }
        val weight = FloatArray(len)
        val accum = wanted.associateWith { Array(2) { FloatArray(len) } }

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            sessionOptions(this)
        }
        env.createSession(modelFile.absolutePath, opts).use { session ->
            val inputName = session.inputNames.firstOrNull() ?: "mix"
            repeat(chunks) { ci ->
                if (isCancelled()) throw InterruptedException("cancelled")
                val start = ci * stride
                val end = minOf(start + chunk, len)
                val clen = (end - start).coerceAtLeast(0)
                val x = FloatArray(2 * chunk)
                if (clen > 0) {
                    mix.left.copyInto(x, 0, start, end)
                    mix.right.copyInto(x, chunk, start, end)
                }
                val shape = longArrayOf(1, 2, chunk.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val output = result[0] as? OnnxTensor ?: error("HT-Demucs output is not a tensor")
                        val fb = output.floatBuffer
                        val expected = spec.stems.size * 2 * chunk
                        require(fb.remaining() >= expected) {
                            "Unexpected HT-Demucs output: ${fb.remaining()} floats, expected at least $expected"
                        }
                        for (kind in wanted) {
                            val si = spec.stems.indexOf(kind)
                            val target = accum.getValue(kind)
                            for (ch in 0..1) {
                                val base = (si * 2 + ch) * chunk
                                val dest = target[ch]
                                for (i in 0 until clen) {
                                    dest[start + i] += fb.get(base + i) * window[i]
                                }
                            }
                        }
                    }
                }
                for (i in 0 until clen) weight[start + i] += window[i]
                progress((ci + 1f) / chunks)
            }
        }

        val separated = wanted.associateWith { kind ->
            val a = accum.getValue(kind)
            val l = FloatArray(len) { i -> if (weight[i] > 1e-8f) a[0][i] / weight[i] else 0f }
            val r = FloatArray(len) { i -> if (weight[i] > 1e-8f) a[1][i] / weight[i] else 0f }
            val atModelRate = AudioBuffer(l, r, spec.sampleRate)
            if (originalRate == spec.sampleRate) atModelRate else Resampler.resample(atModelRate, originalRate)
        }
        progress(1f)
        return MultiStems(separated)
    }
}
