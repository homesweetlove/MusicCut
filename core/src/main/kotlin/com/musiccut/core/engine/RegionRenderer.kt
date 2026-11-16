package com.musiccut.core.engine

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.dsp.dbToGain
import com.musiccut.core.project.Region

object RegionRenderer {
    /** Smoothstep fade curve (0..1). */
    private fun fade(t: Float): Float = t * t * (3f - 2f * t)

    /**
     * Adds [region] to out[offset until offset+frames] where out[offset] corresponds to timeline
     * frame [blockStart].
     */
    fun mix(
        region: Region,
        source: AudioBuffer,
        blockStart: Long,
        frames: Int,
        outL: FloatArray,
        outR: FloatArray,
        offset: Int = 0,
    ) {
        if (region.muted) return
        val blockEnd = blockStart + frames
        if (region.end <= blockStart || region.start >= blockEnd) return
        val from = maxOf(region.start, blockStart)
        val to = minOf(region.end, blockEnd)
        val g = dbToGain(region.gainDb)
        val srcL = source.left
        val srcR = source.right
        val srcFrames = source.frames
        val fi = region.fadeIn
        val fo = region.fadeOut
        for (t in from until to) {
            val rel = t - region.start
            val si = if (region.reversed) region.sourceOffset + (region.length - 1 - rel) else region.sourceOffset + rel
            if (si < 0 || si >= srcFrames) continue
            var gain = g
            // sample-centred fade positions make fade-out and fade-in exact complements in crossfades
            if (fi > 0 && rel < fi) gain *= fade((rel + 0.5f) / fi)
            val remain = region.length - rel
            if (fo > 0 && remain <= fo) gain *= fade((remain - 0.5f) / fo)
            val idx = offset + (t - blockStart).toInt()
            val s = si.toInt()
            outL[idx] += srcL[s] * gain
            outR[idx] += srcR[s] * gain
        }
    }
}
