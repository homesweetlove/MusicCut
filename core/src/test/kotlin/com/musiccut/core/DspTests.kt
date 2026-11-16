package com.musiccut.core

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.Mp3Encoder
import com.musiccut.core.dsp.Convolver
import com.musiccut.core.dsp.Dft
import com.musiccut.core.dsp.LoudnessMeter
import com.musiccut.core.dsp.NoteSegmenter
import com.musiccut.core.dsp.PitchDetector
import com.musiccut.core.dsp.TempoDetector
import com.musiccut.core.dsp.TimePitch
import com.musiccut.core.separation.MdxModelSpec
import com.musiccut.core.separation.MdxNetSeparator
import com.musiccut.core.separation.SeparationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DspTests {
    private val sr = 44100

    private fun sine(freq: Double, seconds: Double, amp: Double = 0.5) =
        FloatArray((sr * seconds).toInt()) { (sin(2 * PI * freq * it / sr) * amp).toFloat() }

    @Test fun bluesteinMatchesNaiveDft() {
        val n = 30
        val x = FloatArray(n) { sin(it * 0.7).toFloat() + cos(it * 0.13).toFloat() }
        val re = x.copyOf(); val im = FloatArray(n)
        Dft(n).forward(re, im)
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) { sr += x[t] * cos(2 * PI * k * t / n); si -= x[t] * sin(2 * PI * k * t / n) }
            assertEquals(sr.toFloat(), re[k], 1e-3f)
            assertEquals(si.toFloat(), im[k], 1e-3f)
        }
        Dft(n).inverse(re, im)
        for (t in 0 until n) assertEquals(x[t], re[t], 1e-4f)
    }

    @Test fun loudnessOfReferenceSine() {
        // EBU Tech 3341: 1 kHz stereo sine at -23 dBFS reads -23 LUFS
        val amp = Math.pow(10.0, -23.0 / 20)
        val s = sine(1000.0, 10.0, amp)
        val r = LoudnessMeter.measure(AudioBuffer(s, s.copyOf(), sr))
        assertEquals(-23f, r.integratedLufs, 0.3f)
        assertEquals(-23f, r.truePeakDb, 0.2f)
    }

    @Test fun stretchKeepsPitchAndChangesLength() {
        val s = sine(440.0, 2.0)
        val out = TimePitch.stretch(AudioBuffer(s, s.copyOf(), sr), 1.5)
        assertEquals((s.size * 1.5).toInt(), out.frames)
        val hz = PitchDetector(sr).detect(out.left, out.frames / 2)
        assertEquals(440f, hz, 3f)
    }

    @Test fun pitchShiftKeepsLengthAndChangesPitch() {
        val s = sine(440.0, 2.0)
        val out = TimePitch.pitchShift(AudioBuffer(s, s.copyOf(), sr), 3.0)
        assertEquals(s.size, out.frames)
        val hz = PitchDetector(sr).detect(out.left, out.frames / 2)
        assertEquals(523.25f, hz, 5f)
    }

    @Test fun yinFindsPitchAndNotes() {
        val a = sine(220.0, 0.5); val b = sine(330.0, 0.5)
        val x = a + FloatArray(sr / 10) + b
        val notes = NoteSegmenter.detectNotes(x, sr)
        assertTrue("notes: $notes", notes.size >= 2)
        assertEquals(57f, notes.first().midi, 0.3f) // A3
        assertEquals(64.02f, notes.last().midi, 0.3f) // E4
        assertEquals(62f, PitchDetector.snap(61.6f, 0, PitchDetector.SCALES.getValue("Major")), 0f)
    }

    @Test fun tempoDetection() {
        val bpm = 128.0
        val x = FloatArray(sr * 12)
        val period = sr * 60.0 / bpm
        var b = 0
        while (b * period < x.size) {
            val start = (b * period).toInt()
            for (i in 0 until 2000) if (start + i < x.size) x[start + i] += (sin(2 * PI * 60 * i / sr) * Math.exp(-i / 400.0)).toFloat()
            b++
        }
        val est = TempoDetector.detect(AudioBuffer(x, x.copyOf(), sr))
        assertEquals(128f, est.bpm, 1.5f)
    }

    @Test fun convolverMatchesDirectConvolution() {
        val ir = FloatArray(1500) { (Math.exp(-it / 300.0) * sin(it * 0.3)).toFloat() }
        val x = FloatArray(4000) { if (it % 700 == 0) 1f else 0f }
        val c = Convolver(ir, ir, 256)
        val l = x.copyOf(); val r = x.copyOf()
        c.process(l, r, 0, x.size)
        // output delayed by one block
        for (n in 1000 until 3000 step 37) {
            var ref = 0f
            val m = n - 256
            for (k in ir.indices) if (m - k >= 0) ref += ir[k] * x[m - k]
            assertEquals(ref, l[n], 1e-3f)
        }
    }

    @Test fun mp3EncoderProducesValidFrames() {
        val s = sine(440.0, 3.0)
        val out = ByteArrayOutputStream()
        Mp3Encoder.encode(AudioBuffer(s, s.copyOf(), sr), out, 192)
        val bytes = out.toByteArray()
        assertTrue(bytes.size > 0)
        // first frame header sync: 0xFFE / 0xFFF
        val i = bytes.indices.first { bytes[it] == 0xFF.toByte() && (bytes[it + 1].toInt() and 0xE0) == 0xE0 }
        assertTrue(i < 1024)
        val expected = 192_000 / 8 * 3.0
        assertTrue("size ${bytes.size}", abs(bytes.size - expected) < expected * 0.1)
    }

    /** Parity with the Python reference (needs -PmdxModel and -PmdxRef, skipped otherwise). */
    @Test fun mdxMatchesReferenceImplementation() {
        val model = System.getProperty("mdxModel")
        val ref = System.getProperty("mdxRef")
        assumeTrue(model != null && ref != null)
        fun readF32(f: File): FloatArray {
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bb.remaining() / 4) { bb.float }
        }
        val input = readF32(File(ref, "in.f32"))
        val half = input.size / 2
        val mix = AudioBuffer(input.copyOfRange(0, half), input.copyOfRange(half, input.size), sr)
        val expected = readF32(File(ref, "ref_vocals.f32"))
        val stems = MdxNetSeparator(File(model!!), MdxModelSpec.VOC_FT).separate(mix, SeparationOptions())
        var err = 0.0; var energy = 0.0
        for (i in 0 until half) {
            val d = stems.vocals.left[i] - expected[i]
            err += d * d; energy += expected[i] * expected[i]
        }
        val rel = sqrt(err / energy)
        println("MDX relative error vs reference: $rel")
        assertTrue("relative error $rel", rel < 0.01)
        for (i in 0 until half step 997) assertEquals(mix.left[i], stems.vocals.left[i] + stems.instrumental.left[i], 1e-4f)
    }
}
