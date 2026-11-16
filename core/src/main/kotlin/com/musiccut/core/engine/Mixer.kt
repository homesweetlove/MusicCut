package com.musiccut.core.engine

import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.PluginChain
import com.musiccut.core.project.Project
import com.musiccut.core.project.SourcePool
import com.musiccut.core.project.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [Project] block by block, following Logic's signal flow:
 * regions → track inserts (with optional side-chain keys) → pre-fader sends → volume/pan
 * (automatable) → post-fader sends → Stereo Out or an aux (summing stack); aux buses → bus
 * inserts → volume/pan → Stereo Out; then master inserts and master volume.
 * One instance per audio thread (it owns plugin state). Not thread-safe except for [meters] and
 * [pluginInstance], which the UI may read.
 */
class Mixer(private val pool: SourcePool, val sampleRate: Int, private val maxBlock: Int = 4096) {
    private class Strip(sr: Int, n: Int) {
        val chain = PluginChain(sr)
        val l = FloatArray(n)
        val r = FloatArray(n)
        val keyL = FloatArray(n)
        val keyR = FloatArray(n)
    }

    private val strips = HashMap<String, Strip>()
    private val busStrips = HashMap<String, Strip>()
    private val master = PluginChain(sampleRate)
    private val live = HashSet<String>()

    /** Latest peak levels (linear) keyed by track id, bus id and [MASTER] (+ "_L"/"_R" for master channels). */
    val meters = java.util.concurrent.ConcurrentHashMap<String, Float>()

    @Volatile private var chains: Map<String, PluginChain> = emptyMap()

    /** Finds the running plugin instance for a slot on any track, bus or the master. */
    fun pluginInstance(slotId: String): AudioPlugin? {
        chains.values.forEach { c -> c.instance(slotId)?.let { return it } }
        return master.instance(slotId)
    }

    fun render(project: Project, startFrame: Long, frames: Int, outL: FloatArray, outR: FloatArray) {
        // automation is evaluated per block, so use short blocks when any lane exists
        val block = if (project.tracks.any { t -> t.automation.any { it.enabled && it.points.isNotEmpty() } }) min(maxBlock, 128) else maxBlock
        var done = 0
        while (done < frames) {
            val n = min(block, frames - done)
            renderBlock(project, startFrame + done, n, outL, outR, done)
            done += n
        }
    }

    private val keyPairs = HashMap<String, Pair<FloatArray, FloatArray>>()
    private val keySource: (String) -> Pair<FloatArray, FloatArray>? = { id ->
        strips[id]?.let { st -> keyPairs.getOrPut(id) { st.keyL to st.keyR } }
    }

    private fun renderBlock(project: Project, start: Long, n: Int, outL: FloatArray, outR: FloatArray, offset: Int) {
        java.util.Arrays.fill(outL, offset, offset + n, 0f)
        java.util.Arrays.fill(outR, offset, offset + n, 0f)
        live.clear()
        var structureChanged = false

        for (bus in project.buses) {
            val s = busStrips.getOrPut(bus.id) { structureChanged = true; Strip(sampleRate, maxBlock) }
            java.util.Arrays.fill(s.l, 0, n, 0f)
            java.util.Arrays.fill(s.r, 0, n, 0f)
        }

        // pass 1: raw region audio for every track (also the side-chain keys)
        for (track in project.tracks) {
            live += track.id
            val strip = strips.getOrPut(track.id) {
                structureChanged = true
                Strip(sampleRate, maxBlock).also { it.chain.sidechainSource = keySource }
            }
            java.util.Arrays.fill(strip.l, 0, n, 0f)
            java.util.Arrays.fill(strip.r, 0, n, 0f)
            for (region in track.regions) {
                if (region.start >= start + n || region.end <= start) continue
                val src = pool[region.sourceId] ?: continue
                RegionRenderer.mix(region, src, start, n, strip.l, strip.r)
            }
            System.arraycopy(strip.l, 0, strip.keyL, 0, n)
            System.arraycopy(strip.r, 0, strip.keyR, 0, n)
        }

        // pass 2: inserts, sends, fader, routing
        val anySolo = project.tracks.any { it.solo }
        for (track in project.tracks) {
            val strip = strips[track.id] ?: continue
            strip.chain.sync(track.plugins)
            strip.chain.tempo = project.tempo
            applyPluginAutomation(track, strip.chain, start)
            val audible = !track.muted && (!anySolo || track.solo)
            strip.chain.process(strip.l, strip.r, 0, n)
            if (audible) sends(project, track, strip, n, start, pre = true)

            val volDb = Automation.lane(track, Automation.VOLUME)?.let { FaderLaw.posToDb(Automation.valueAt(it.points, start)!!) } ?: track.volumeDb
            val pan = Automation.lane(track, Automation.PAN)?.let { Automation.valueAt(it.points, start)!! * 2f - 1f } ?: track.pan
            val vol = dbToGain(volDb)
            val gl = vol * min(1f, 1f - pan)
            val gr = vol * min(1f, 1f + pan)
            val dest = track.outputBusId?.let { busStrips[it] }
            var pk = 0f
            for (i in 0 until n) {
                val l = strip.l[i] * gl
                val r = strip.r[i] * gr
                strip.l[i] = l
                strip.r[i] = r
                pk = max(pk, max(abs(l), abs(r)))
            }
            if (audible) {
                if (dest != null) {
                    for (i in 0 until n) { dest.l[i] += strip.l[i]; dest.r[i] += strip.r[i] }
                } else {
                    for (i in 0 until n) { outL[offset + i] += strip.l[i]; outR[offset + i] += strip.r[i] }
                }
                sends(project, track, strip, n, start, pre = false)
            }
            meters[track.id] = if (audible) pk else 0f
        }

        for (bus in project.buses) {
            live += bus.id
            val s = busStrips[bus.id] ?: continue
            s.chain.sync(bus.plugins)
            s.chain.tempo = project.tempo
            s.chain.process(s.l, s.r, 0, n)
            val vol = if (bus.muted) 0f else dbToGain(bus.volumeDb)
            val gl = vol * min(1f, 1f - bus.pan)
            val gr = vol * min(1f, 1f + bus.pan)
            var pk = 0f
            for (i in 0 until n) {
                val l = s.l[i] * gl
                val r = s.r[i] * gr
                pk = max(pk, max(abs(l), abs(r)))
                outL[offset + i] += l
                outR[offset + i] += r
            }
            meters[bus.id] = pk
        }

        if (strips.size + busStrips.size > live.size) {
            strips.keys.retainAll(live)
            busStrips.keys.retainAll(live)
            keyPairs.keys.retainAll(live)
            meters.keys.retainAll(live + MASTER + "${MASTER}_L" + "${MASTER}_R")
            structureChanged = true
        }
        if (structureChanged) {
            chains = (strips.mapValues { it.value.chain } + busStrips.mapValues { it.value.chain })
        }

        master.sync(project.masterPlugins)
        master.tempo = project.tempo
        master.process(outL, outR, offset, n)
        val mv = dbToGain(project.masterVolumeDb)
        var ml = 0f
        var mr = 0f
        for (i in offset until offset + n) {
            outL[i] *= mv
            outR[i] *= mv
            ml = max(ml, abs(outL[i]))
            mr = max(mr, abs(outR[i]))
        }
        meters[MASTER] = max(ml, mr)
        meters["${MASTER}_L"] = ml
        meters["${MASTER}_R"] = mr
    }

    private fun sends(project: Project, track: Track, strip: Strip, n: Int, start: Long, pre: Boolean) {
        for (send in track.sends) {
            if (send.preFader != pre) continue
            val bus = busStrips[send.busId] ?: continue
            if (track.outputBusId == send.busId) continue // avoid doubling into our own stack
            val lvl = Automation.lane(track, Automation.sendTarget(send.busId))
                ?.let { FaderLaw.posToDb(Automation.valueAt(it.points, start)!!) } ?: send.levelDb
            val g = dbToGain(lvl)
            for (i in 0 until n) {
                bus.l[i] += strip.l[i] * g
                bus.r[i] += strip.r[i] * g
            }
        }
    }

    private fun applyPluginAutomation(track: Track, chain: PluginChain, frame: Long) {
        for (lane in track.automation) {
            if (!lane.enabled || lane.points.isEmpty() || !lane.target.startsWith("plugin:")) continue
            val parts = lane.target.removePrefix("plugin:").split(":", limit = 2)
            if (parts.size != 2) continue
            chain.automate(parts[0], parts[1], Automation.valueAt(lane.points, frame)!!)
        }
    }

    /** Clears reverb tails / delay lines, e.g. after a seek. */
    fun reset() {
        strips.values.forEach { it.chain.reset() }
        busStrips.values.forEach { it.chain.reset() }
        master.reset()
    }

    companion object {
        const val MASTER = "master"
    }
}
