package com.musiccut.core

import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.separation.HtDemucsModelSpec
import com.musiccut.core.separation.MultiStems
import com.musiccut.core.separation.StemKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StemSeparationTests {
    @Test
    fun sixStemModelUsesPublishedOutputOrder() {
        val spec = HtDemucsModelSpec.SIX_STEM_FP16
        assertEquals(44_100, spec.sampleRate)
        assertEquals(343_980, spec.segmentFrames)
        assertEquals(
            listOf(StemKind.DRUMS, StemKind.BASS, StemKind.OTHER, StemKind.VOCALS, StemKind.GUITAR, StemKind.PIANO),
            spec.stems,
        )
        assertEquals("7ce55792e2231c93fbf92de95f5fd5b3a5e6c89f7db690dfd693e8f1dce56869", spec.sha256)
    }

    @Test
    fun vocalStemCanProducePhaseExactInstrumentalAgainstReference() {
        val mix = AudioBuffer(floatArrayOf(1f, 0.25f, -0.5f), floatArrayOf(0.4f, -0.2f, 0.8f), 44_100)
        val vocals = AudioBuffer(floatArrayOf(0.2f, 0.1f, -0.1f), floatArrayOf(0.1f, -0.1f, 0.3f), 44_100)
        val inst = MultiStems(mapOf(StemKind.VOCALS to vocals)).instrumental(mix)
        assertArrayEquals(floatArrayOf(0.8f, 0.15f, -0.4f), inst.left, 1e-6f)
        assertArrayEquals(floatArrayOf(0.3f, -0.1f, 0.5f), inst.right, 1e-6f)
    }
}
