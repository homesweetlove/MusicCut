package com.musiccut.core

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.dsp.PitchDetector
import com.musiccut.core.edit.EditOps
import com.musiccut.core.edit.TakeOps
import com.musiccut.core.engine.Automation
import com.musiccut.core.engine.FaderLaw
import com.musiccut.core.engine.Mixer
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.AudioSourceInfo
import com.musiccut.core.project.AutoPoint
import com.musiccut.core.project.AutomationLane
import com.musiccut.core.project.AuxBus
import com.musiccut.core.project.PluginSlot
import com.musiccut.core.project.Project
import com.musiccut.core.project.Region
import com.musiccut.core.project.SourcePool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EngineFeatureTests {
    private val sr = 44100

    private fun setup(buffers: List<AudioBuffer>): Pair<Project, SourcePool> {
        val pool = SourcePool()
        var p = Project()
        buffers.forEachIndexed { i, b ->
            pool.put("s$i", b)
            p = p.copy(sources = p.sources + AudioSourceInfo("s$i", "s$i", b.frames.toLong(), sr, "s$i.wav"))
            val (np, t) = EditOps.addTrack(p, "T$i")
            p = EditOps.addRegion(np, t.id, Region(id = "r$i", sourceId = "s$i", name = "r", start = 0, sourceOffset = 0, length = b.frames.toLong()))
        }
        return p to pool
    }

    private fun render(p: Project, pool: SourcePool, from: Long, frames: Int): FloatArray {
        val l = FloatArray(frames); val r = FloatArray(frames)
        Mixer(pool, sr).render(p, from, frames, l, r)
        return l
    }

    private fun dc(frames: Int, v: Float = 0.5f) = AudioBuffer(FloatArray(frames) { v }, FloatArray(frames) { v }, sr)

    @Test fun volumeAutomationRamps() {
        val (p0, pool) = setup(listOf(dc(sr)))
        val lane = AutomationLane(target = Automation.VOLUME, points = listOf(AutoPoint(0, FaderLaw.dbToPos(0f)), AutoPoint(sr.toLong(), 0f)))
        val p = p0.updateTrack(p0.tracks[0].id) { it.copy(automation = listOf(lane)) }
        val out = render(p, pool, 0, sr)
        assertEquals(0.5f, out[10], 0.02f)
        assertTrue(out[sr / 2] < 0.45f)
        assertTrue(out[sr - 200] < 0.01f)
    }

    @Test fun pluginParameterAutomation() {
        val (p0, pool) = setup(listOf(dc(sr)))
        val slot = PluginSlot(id = "g", pluginId = "gain", params = PluginRegistry.find("gain")!!.defaults())
        val spec = PluginRegistry.find("gain")!!.params.first { it.id == "gain" }
        val lane = AutomationLane(target = Automation.pluginTarget("g", "gain"), points = listOf(AutoPoint(0, spec.toNormalized(-6.0206f))))
        val p = p0.updateTrack(p0.tracks[0].id) { it.copy(plugins = listOf(slot), automation = listOf(lane)) }
        assertEquals(0.25f, render(p, pool, 0, 1000)[500], 1e-3f)
    }

    @Test fun sidechainDucksTrack() {
        val kick = AudioBuffer(FloatArray(sr) { if (it in 10_000..20_000) 0.9f else 0f }, FloatArray(sr) { if (it in 10_000..20_000) 0.9f else 0f }, sr)
        val (p0, pool) = setup(listOf(dc(sr, 0.3f), kick))
        val comp = PluginRegistry.find("compressor")!!
        val slot = PluginSlot(pluginId = "compressor", params = comp.defaults() + mapOf("threshold" to -30f, "ratio" to 20f, "attack" to 0.5f, "autoGain" to 0f, "knee" to 0f), sidechainTrackId = p0.tracks[1].id)
        val p = p0.updateTrack(p0.tracks[0].id) { it.copy(plugins = listOf(slot)) }.updateTrack(p0.tracks[1].id) { it.copy(muted = true) }
        val out = render(p, pool, 0, sr)
        assertEquals(0.3f, out[5_000], 0.02f)          // no key: untouched (0.3 is above threshold but key is silent)
        assertTrue("ducked ${out[15_000]}", out[15_000] < 0.1f) // key active: ducked
    }

    @Test fun trackRoutesIntoSummingStack() {
        val (p0, pool) = setup(listOf(dc(2000)))
        val bus = AuxBus(id = "stack", name = "Stack", volumeDb = -6.0206f)
        val p = p0.copy(buses = listOf(bus)).updateTrack(p0.tracks[0].id) { it.copy(outputBusId = "stack") }
        assertEquals(0.25f, render(p, pool, 0, 2000)[1000], 1e-3f)
    }

    @Test fun crossfadeOfSplitIsTransparent() {
        val src = AudioBuffer(FloatArray(20_000) { sin(it * 0.01f) }, FloatArray(20_000) { sin(it * 0.01f) }, sr)
        val (p0, pool) = setup(listOf(src))
        val split = EditOps.split(p0, 10_000)
        val ids = split.tracks[0].regions.map { it.id }.toSet()
        val xf = EditOps.crossfade(split, ids, 2_000)
        val regions = xf.tracks[0].regions
        assertEquals(2_000, regions[0].fadeOut)
        assertEquals(2_000, regions[1].fadeIn)
        val a = render(p0, pool, 0, 20_000)
        val b = render(xf, pool, 0, 20_000)
        for (i in 8_000 until 12_000 step 13) assertEquals(a[i], b[i], 1e-4f)
    }

    @Test fun takesPreserveOldRecordingAndComp() {
        val (p0, pool) = setup(listOf(dc(10_000, 0.2f)))
        pool.put("new", dc(10_000, 0.7f))
        val tid = p0.tracks[0].id
        var p = p0.copy(sources = p0.sources + AudioSourceInfo("new", "new", 10_000, sr, "new.wav"))
        p = TakeOps.addTake(p, tid, Region(sourceId = "new", name = "take2", start = 0, sourceOffset = 0, length = 10_000))
        val lanes = p.track(tid)!!.takeLanes
        assertEquals(2, lanes.size)
        assertEquals(0.7f, render(p, pool, 0, 10_000)[5_000], 1e-4f)
        // comp: use the first take for 2000..4000
        p = TakeOps.compRange(p, tid, lanes[0].id, 2_000, 4_000)
        val out = render(p, pool, 0, 10_000)
        assertEquals(0.2f, out[3_000], 1e-4f)
        assertEquals(0.7f, out[5_000], 1e-4f)
    }

    private fun sine(freq: Double, frames: Int) = FloatArray(frames) { (sin(2 * PI * freq * it / sr) * 0.5).toFloat() }

    @Test fun pitchCorrectionSnapsToScale() {
        val plugin = PluginRegistry.create("pitch_correction", sr)!!
        plugin.setParam("scale", 0f) // chromatic
        plugin.setParam("response", 1f)
        val l = sine(452.0, sr * 2)
        val r = l.copyOf()
        var pos = 0
        while (pos < l.size) { val n = minOf(512, l.size - pos); plugin.run(l, r, pos, n); pos += n }
        val hz = PitchDetector(sr).detect(l, sr)
        assertEquals(440f, hz, 4f)
    }

    @Test fun vocalTransformerUnityAndShift() {
        fun run(pitch: Float): FloatArray {
            val p = PluginRegistry.create("vocal_transformer", sr)!!
            p.setParam("pitch", pitch)
            // harmonic-rich source (like a voice): 150 Hz with 25 harmonics, flat envelope
            val l = FloatArray(sr) { i -> (1..25).sumOf { h -> sin(2 * PI * 150.0 * h * i / sr) / 25.0 }.toFloat() * 1.5f }
            val r = l.copyOf()
            var pos = 0
            while (pos < l.size) { val n = minOf(256, l.size - pos); p.run(l, r, pos, n); pos += n }
            return l
        }
        fun rms(x: FloatArray): Float { var s = 0f; for (i in sr / 2 until sr) s += x[i] * x[i]; return kotlin.math.sqrt(s / (sr / 2)) }
        val ref = FloatArray(sr) { i -> (1..25).sumOf { h -> sin(2 * PI * 150.0 * h * i / sr) / 25.0 }.toFloat() * 1.5f }
        val same = run(0f)
        assertEquals("unity gain", rms(ref), rms(same), rms(ref) * 0.15f)
        assertEquals(150f, PitchDetector(sr).detect(same, sr / 2), 3f)
        val up = run(12f)
        assertEquals(300f, PitchDetector(sr).detect(up, sr / 2), 6f)
    }

    @Test fun spaceDesignerProducesTail() {
        val p = PluginRegistry.create("space_designer", sr)!!
        p.setParam("mix", 100f)
        val warm = FloatArray(512); p.run(warm, warm.copyOf(), 0, 512) // triggers IR build
        Thread.sleep(1500)
        val l = FloatArray(sr).also { it[0] = 1f }
        val r = l.copyOf()
        var pos = 0
        while (pos < l.size) { val n = minOf(512, l.size - pos); p.run(l, r, pos, n); pos += n }
        var e = 0f
        for (i in sr / 4 until sr / 2) e += l[i] * l[i]
        assertTrue("reverb tail energy $e", e > 1e-6f)
    }
}
