package com.musiccut.core

import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.ExternalPluginRef
import com.musiccut.core.project.PluginSlot
import com.musiccut.core.project.Project
import com.musiccut.core.project.ProjectImportInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExternalPluginTests {
    @Test
    fun externalPluginMetadataSurvivesProjectRoundTrip() {
        val ref = ExternalPluginRef(
            format = "logic-au",
            name = "Example AU",
            manufacturer = "Acme",
            type = "aufx",
            subtype = "Test",
            presetName = "Wide",
            opaqueStateBase64 = "AQIDBA==",
        )
        val slot = PluginSlot(
            id = "slot-1",
            pluginId = "external.logic.au.aufx.test.acme",
            params = emptyMap(),
            external = ref,
        )
        val original = Project(
            name = "Logic import",
            masterPlugins = listOf(slot),
            importInfo = ProjectImportInfo("logicx", "Song.logicx", listOf("best effort")),
        )

        val reopened = Project.fromJson(original.toJson())
        val restoredSlot = reopened.masterPlugins.single()
        assertEquals(ref, restoredSlot.external)
        assertEquals("logicx", reopened.importInfo?.sourceFormat)

        PluginRegistry.ensureExternal(reopened)
        assertNotNull(PluginRegistry.find(restoredSlot.pluginId))

        val plugin = PluginRegistry.create(restoredSlot, 44_100)
        assertNotNull(plugin)
        val left = floatArrayOf(-0.5f, 0f, 0.25f, 0.9f)
        val right = floatArrayOf(0.4f, -0.1f, 0.7f, -0.8f)
        val expectedLeft = left.copyOf()
        val expectedRight = right.copyOf()
        plugin!!.run(left, right, 0, left.size)
        assertArrayEquals(expectedLeft, left, 0f)
        assertArrayEquals(expectedRight, right, 0f)
    }

    @Test
    fun knownLogicNativePluginsResolveToMusicCutEffects() {
        assertEquals("channel_eq", PluginRegistry.logicNativePluginId(236))
        assertEquals("compressor", PluginRegistry.logicNativePluginId(154))
        assertEquals("limiter", PluginRegistry.logicNativePluginId(199))
        assertEquals("noise_gate", PluginRegistry.logicNativePluginId(179))
    }
}
