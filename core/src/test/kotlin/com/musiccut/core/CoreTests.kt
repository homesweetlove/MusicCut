package com.musiccut.core

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.Resampler
import com.musiccut.core.audio.WavCodec
import com.musiccut.core.audio.WavFormat
import com.musiccut.core.dsp.Fft
import com.musiccut.core.edit.EditOps
import com.musiccut.core.edit.UndoStack
import com.musiccut.core.engine.Mixer
import com.musiccut.core.engine.OfflineRenderer
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.AudioSourceInfo
import com.musiccut.core.project.PluginSlot
import com.musiccut.core.project.Project
import com.musiccut.core.project.Region
import com.musiccut.core.project.SourcePool
import com.musiccut.core.separation.SeparationOptions
import com.musiccut.core.separation.SpectralVocalSeparator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class CoreTests {
    private val sr = 44100

    private fun ramp(frames: Int) = AudioBuffer(FloatArray(frames) { it.toFloat() }, FloatArray(frames) { -it.toFloat() }, sr)

    private fun projectWith(buffer: AudioBuffer): Pair<Project, SourcePool> {
        val pool = SourcePool()
        pool.put("s1", buffer)
        var p = Project(sources = listOf(AudioSourceInfo("s1", "src", buffer.frames.toLong(), sr, "s1.wav")))
        val (p2, t) = EditOps.addTrack(p, "T1")
        p = EditOps.addRegion(p2, t.id, Region(id = "r1", sourceId = "s1", name = "r", start = 0, sourceOffset = 0, length = buffer.frames.toLong()))
        return p to pool
    }

    private fun renderDry(p: Project, pool: SourcePool, from: Long, frames: Int): AudioBuffer {
        val l = FloatArray(frames); val r = FloatArray(frames)
        Mixer(pool, sr).render(p, from, frames, l, r)
        return AudioBuffer(l, r, sr)
    }

    @Test fun wavRoundTrip() {
        val b = AudioBuffer(FloatArray(1000) { sin(it * 0.05f) * 0.5f }, FloatArray(1000) { sin(it * 0.03f) * 0.25f }, 48000)
        for (fmt in WavFormat.values()) {
            val out = ByteArrayOutputStream()
            WavCodec.write(b, out, fmt)
            val back = WavCodec.read(ByteArrayInputStream(out.toByteArray()))
            assertEquals(48000, back.sampleRate)
            assertEquals(1000, back.frames)
            val tol = if (fmt == WavFormat.PCM16) 1e-4f else 1e-6f
            for (i in 0 until 1000) {
                assertEquals(b.left[i], back.left[i], tol)
                assertEquals(b.right[i], back.right[i], tol)
            }
        }
    }

    @Test fun fftRoundTrip() {
        val fft = Fft(1024)
        val re = FloatArray(1024) { sin(it * 0.1f) }
        val im = FloatArray(1024)
        val orig = re.copyOf()
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in orig.indices) assertEquals(orig[i], re[i], 1e-4f)
    }

    @Test fun splitThenRenderIsIdentical() {
        val (p, pool) = projectWith(ramp(10_000))
        val split = EditOps.split(p, 4_000)
        assertEquals(2, split.tracks[0].regions.size)
        val a = renderDry(p, pool, 0, 10_000)
        val b = renderDry(split, pool, 0, 10_000)
        assertArrayEquals(a.left, b.left, 0f)
    }

    @Test fun rippleCutRemovesTimeAndClosesGap() {
        val (p, pool) = projectWith(ramp(10_000))
        val tid = p.tracks[0].id
        val (cut, clip) = EditOps.rippleCut(p, setOf(tid), 2_000, 5_000)
        assertEquals(3_000, clip.length)
        assertEquals(7_000, cut.contentEnd)
        val out = renderDry(cut, pool, 0, 7_000)
        assertEquals(1999f, out.left[1999], 0f)
        assertEquals(5000f, out.left[2000], 0f)
        assertEquals(9999f, out.left[6999], 0f)

        // insert it back → identical to original
        val (restored, _) = EditOps.rippleInsert(cut, clip, tid, 2_000)
        val back = renderDry(restored, pool, 0, 10_000)
        for (i in 0 until 10_000) assertEquals(i.toFloat(), back.left[i], 0f)
    }

    @Test fun copyPasteReplaces() {
        val (p, pool) = projectWith(ramp(10_000))
        val tid = p.tracks[0].id
        val clip = EditOps.copyRange(p, setOf(tid), 0, 1_000)
        val (pasted, ids) = EditOps.paste(p, clip, tid, 5_000)
        assertEquals(1, ids.size)
        val out = renderDry(pasted, pool, 0, 10_000)
        assertEquals(4999f, out.left[4999], 0f)
        assertEquals(0f, out.left[5000], 0f)
        assertEquals(999f, out.left[5999], 0f)
        assertEquals(6000f, out.left[6000], 0f)
    }

    @Test fun trimAndReverse() {
        val (p, pool) = projectWith(ramp(10_000))
        var q = EditOps.trimStart(p, "r1", 1_000)
        q = EditOps.trimEnd(q, "r1", 3_000)
        val r = q.findRegion("r1")!!.second
        assertEquals(1_000, r.start); assertEquals(1_000, r.sourceOffset); assertEquals(2_000, r.length)
        q = EditOps.reverse(q, setOf("r1"))
        val out = renderDry(q, pool, 1_000, 2_000)
        assertEquals(2999f, out.left[0], 0f)
        assertEquals(1000f, out.left[1999], 0f)
        // splitting a reversed region must not change the sound
        val s = EditOps.split(q, 1_500)
        val out2 = renderDry(s, pool, 1_000, 2_000)
        assertArrayEquals(out.left, out2.left, 0f)
    }

    @Test fun undoRedo() {
        val (p, _) = projectWith(ramp(100))
        val undo = UndoStack<Project>()
        undo.push("split", p)
        val q = EditOps.split(p, 50)
        assertEquals(p, undo.undo(q))
        assertEquals(q, undo.redo(p))
    }

    @Test fun joinProducesSingleRegionWithSameAudio() {
        val (p, pool) = projectWith(ramp(10_000))
        val s = EditOps.split(p, 5_000)
        val ids = s.tracks[0].regions.map { it.id }.toSet()
        val gainChanged = EditOps.setGain(s, setOf(ids.last()), 0f)
        val (joined, created) = OfflineRenderer.join(gainChanged, pool, ids)
        assertEquals(1, created.size)
        assertEquals(1, joined.tracks[0].regions.size)
        val out = renderDry(joined, pool, 0, 10_000)
        for (i in 0 until 10_000 step 997) assertEquals(i.toFloat(), out.left[i], 1e-3f)
    }

    @Test fun everyPluginIsStableAndFinite() {
        val frames = sr
        for (d in PluginRegistry.all) {
            val plugin = d.factory(sr)
            val l = FloatArray(frames) { (sin(2 * PI * 440 * it / sr) * 0.8).toFloat() + if (it % 5000 == 0) 0.9f else 0f }
            val r = FloatArray(frames) { (sin(2 * PI * 660 * it / sr) * 0.8).toFloat() }
            // random-ish params too
            for (spec in d.params) plugin.setParam(spec.id, spec.fromNormalized(0.8f))
            var pos = 0
            while (pos < frames) {
                val n = minOf(512, frames - pos)
                plugin.run(l, r, pos, n)
                pos += n
            }
            assertTrue("${d.id} produced non-finite output", l.all { it.isFinite() } && r.all { it.isFinite() })
            assertTrue("${d.id} exploded", l.all { abs(it) < 100f })
            // every preset must refer to real params
            for ((name, preset) in d.presets) for (k in preset.keys) {
                assertNotNull("${d.id} preset $name has unknown param $k", d.params.firstOrNull { it.id == k })
            }
        }
    }

    @Test fun limiterRespectsCeiling() {
        val lim = PluginRegistry.create("limiter", sr)!!
        lim.setParam("gain", 12f)
        lim.setParam("ceiling", -1f)
        val l = FloatArray(sr) { (sin(2 * PI * 100 * it / sr) * 0.9).toFloat() }
        val r = l.copyOf()
        lim.run(l, r, 0, sr)
        val ceil = 0.8913f + 1e-4f
        assertTrue(l.all { abs(it) <= ceil })
    }

    @Test fun pluginChainOnTrackAndMaster() {
        val (p, pool) = projectWith(AudioBuffer(FloatArray(4410) { 0.5f }, FloatArray(4410) { 0.5f }, sr))
        val gain = PluginSlot(pluginId = "gain", params = mapOf("gain" to -6.0206f))
        val q = p.updateTrack(p.tracks[0].id) { it.copy(plugins = listOf(gain)) }.copy(masterPlugins = listOf(gain.copy(id = "m")))
        val out = renderDry(q, pool, 0, 1000)
        assertEquals(0.125f, out.left[500], 1e-4f)
    }

    @Test fun separationReconstructsAndExtractsCenter() {
        val frames = sr * 2
        // "vocal": centered 440 Hz tone; "instrument": hard-left 220 Hz + hard-right 3 kHz
        val voice = FloatArray(frames) { (sin(2 * PI * 440 * it / sr) * 0.4).toFloat() }
        val l = FloatArray(frames) { voice[it] + (sin(2 * PI * 220 * it / sr) * 0.4).toFloat() }
        val r = FloatArray(frames) { voice[it] + (sin(2 * PI * 3000 * it / sr) * 0.4).toFloat() }
        val input = AudioBuffer(l, r, sr)
        val stems = SpectralVocalSeparator().separate(input, SeparationOptions(percussionSuppression = 0f))
        // Perfect reconstruction
        for (i in 0 until frames step 101) {
            assertEquals(l[i], stems.vocals.left[i] + stems.instrumental.left[i], 1e-4f)
        }
        fun rmsDiff(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in sr / 2 until frames - sr / 2) { val d = a[i] - b[i]; s += d * d }
            return sqrt(s / (frames - sr))
        }
        fun rms(a: FloatArray): Double {
            var s = 0.0
            for (i in sr / 2 until frames - sr / 2) s += a[i] * a[i]
            return sqrt(s / (frames - sr))
        }
        val err = rmsDiff(stems.vocals.left, voice) / rms(voice)
        assertTrue("vocal error too large: $err", err < 0.35)
        assertFalse(stems.vocals.left.any { it.isNaN() })
    }

    @Test fun resamplerKeepsFrequency() {
        val src = AudioBuffer(FloatArray(48000) { sin(2 * PI * 1000 * it / 48000).toFloat() }, FloatArray(48000), 48000)
        val out = Resampler.resample(src, 44100)
        assertEquals(44100, out.frames)
        for (i in 1000 until 1100) assertEquals(sin(2 * PI * 1000 * i / 44100).toFloat(), out.left[i], 0.01f)
    }

    @Test fun projectJsonRoundTrip() {
        val (p, _) = projectWith(ramp(100))
        assertEquals(p, Project.fromJson(p.toJson()))
    }
}

class AnalysisTests {
    @Test fun eqResponseShowsBoost() {
        val d = PluginRegistry.find("channel_eq")!!
        val resp = com.musiccut.core.plugin.PluginAnalysis.frequencyResponse(d, d.defaults() + mapOf("b3Freq" to 1000f, "b3Gain" to 12f, "b3Q" to 1f), points = 64)
        val idx = (0 until 64).minBy { abs(com.musiccut.core.plugin.PluginAnalysis.frequencyAt(it, 64) - 1000f) }
        assertEquals(12f, resp[idx], 1.5f)
        assertEquals(0f, resp[3], 0.5f)
    }
}

class ArrangementTests {
    private val sr = 44100

    private fun setup(): Pair<Project, SourcePool> {
        val pool = SourcePool()
        val buf = AudioBuffer(FloatArray(12_000) { it.toFloat() }, FloatArray(12_000) { it.toFloat() }, sr)
        pool.put("s", buf)
        var p = Project(sources = listOf(AudioSourceInfo("s", "s", 12_000, sr, "s.wav")))
        val (p1, t1) = EditOps.addTrack(p, "A")
        val (p2, _) = EditOps.addTrack(p1, "B (empty)")
        p = EditOps.addRegion(p2, t1.id, Region(sourceId = "s", name = "r", start = 0, sourceOffset = 0, length = 12_000))
        p = com.musiccut.core.edit.ArrangementOps.addSection(p, "Intro", 0, 4_000)
        p = com.musiccut.core.edit.ArrangementOps.addSection(p, "Verse", 4_000, 8_000)
        p = com.musiccut.core.edit.ArrangementOps.addSection(p, "Chorus", 8_000, 12_000)
        p = com.musiccut.core.edit.ArrangementOps.addMarker(p, 9_000, "Hook")
        return p to pool
    }

    private fun render(p: Project, pool: SourcePool, frames: Int): FloatArray {
        val l = FloatArray(frames); val r = FloatArray(frames)
        Mixer(pool, sr).render(p, 0, frames, l, r)
        return l
    }

    @Test fun deleteSectionClosesGap() {
        val (p, pool) = setup()
        val verse = p.sections.first { it.name == "Verse" }
        val q = com.musiccut.core.edit.ArrangementOps.deleteSection(p, verse.id)
        assertEquals(listOf("Intro", "Chorus"), q.sections.map { it.name })
        assertEquals(4_000, q.sections[1].start)
        assertEquals(5_000, q.markers.single().position)
        val out = render(q, pool, 8_000)
        assertEquals(3_999f, out[3_999], 0f)
        assertEquals(8_000f, out[4_000], 0f)
    }

    @Test fun duplicateSectionRepeatsAudio() {
        val (p, pool) = setup()
        val verse = p.sections.first { it.name == "Verse" }
        val q = com.musiccut.core.edit.ArrangementOps.duplicateSection(p, verse.id)
        assertEquals(listOf("Intro", "Verse", "Verse", "Chorus"), q.sections.map { it.name })
        assertEquals(16_000, q.contentEnd)
        val out = render(q, pool, 16_000)
        assertEquals(4_000f, out[4_000], 0f)
        assertEquals(4_000f, out[8_000], 0f)
        assertEquals(8_000f, out[12_000], 0f)
        assertEquals(13_000, q.markers.single().position)
    }

    @Test fun moveSectionSwapsWithNeighbour() {
        val (p, pool) = setup()
        val chorus = p.sections.first { it.name == "Chorus" }
        val q = com.musiccut.core.edit.ArrangementOps.moveSection(p, chorus.id, -1)
        assertEquals(listOf("Intro", "Chorus", "Verse"), q.sections.map { it.name })
        val out = render(q, pool, 12_000)
        assertEquals(8_000f, out[4_000], 0f)
        assertEquals(4_000f, out[8_000], 0f)
        assertEquals(5_000, q.markers.single().position)
        // and back again
        val back = com.musiccut.core.edit.ArrangementOps.moveSection(q, chorus.id, 1)
        assertEquals(listOf("Intro", "Verse", "Chorus"), back.sections.map { it.name })
        val out2 = render(back, pool, 12_000)
        for (i in 0 until 12_000 step 499) assertEquals(i.toFloat(), out2[i], 0f)
    }

    @Test fun auxSendReachesMaster() {
        val (p0, pool) = setup()
        val bus = com.musiccut.core.project.AuxBus(name = "Bus 1")
        val tid = p0.tracks[0].id
        val p = p0.copy(buses = listOf(bus)).updateTrack(tid) {
            it.copy(volumeDb = -120f, sends = listOf(com.musiccut.core.project.Send(bus.id, 0f, preFader = true)))
        }
        val out = render(p, pool, 2_000)
        assertEquals(1_000f, out[1_000], 1e-3f) // fader closed, pre-fader send still feeds the bus
    }

    @Test fun metronomeClicksOnBeats() {
        val p = Project(tempo = 120f)
        val l = FloatArray(sr); val r = FloatArray(sr)
        com.musiccut.core.engine.Metronome(sr).render(p, 0, sr, l, r)
        // 120 bpm → beats every 22050 frames
        assertTrue(abs(l[10]) > 0f)
        assertEquals(0f, l[11_000], 0f)
        assertTrue(l.sliceArray(22_050 until 22_100).any { abs(it) > 0f })
    }

    @Test fun eqAnalyzerTapSeesSignal() {
        val eq = PluginRegistry.create("channel_eq", sr)!!
        val l = FloatArray(4096) { sin(2 * PI * 1000 * it / sr).toFloat() }
        eq.run(l, l.copyOf(), 0, 4096)
        val snap = FloatArray(2048)
        eq.analyzer!!.snapshot(snap)
        val spec = com.musiccut.core.plugin.PluginAnalysis.spectrum(snap, sr, 64)
        val idx = (0 until 64).minBy { abs(com.musiccut.core.plugin.PluginAnalysis.frequencyAt(it, 64) - 1000f) }
        assertTrue(spec[idx] > -20f)
        assertTrue(spec[2] < -40f)
    }
}
