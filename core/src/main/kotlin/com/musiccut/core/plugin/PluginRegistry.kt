package com.musiccut.core.plugin

import com.musiccut.core.plugin.effects.AdaptiveLimiter
import com.musiccut.core.plugin.effects.AmpDesigner
import com.musiccut.core.plugin.effects.AutoFilter
import com.musiccut.core.plugin.effects.ChromaGlow
import com.musiccut.core.plugin.effects.DelayDesigner
import com.musiccut.core.plugin.effects.Ensemble
import com.musiccut.core.plugin.effects.GraphicEq
import com.musiccut.core.plugin.effects.LoudnessMeterPlugin
import com.musiccut.core.plugin.effects.MultiMeter
import com.musiccut.core.plugin.effects.PitchCorrection
import com.musiccut.core.plugin.effects.Ringshifter
import com.musiccut.core.plugin.effects.SpaceDesigner
import com.musiccut.core.plugin.effects.TestOscillator
import com.musiccut.core.plugin.effects.TunerPlugin
import com.musiccut.core.plugin.effects.VocalTransformer
import com.musiccut.core.plugin.effects.Bitcrusher
import com.musiccut.core.plugin.effects.ChannelEq
import com.musiccut.core.plugin.effects.Chorus
import com.musiccut.core.plugin.effects.ChromaVerb
import com.musiccut.core.plugin.effects.Compressor
import com.musiccut.core.plugin.effects.CutFilter
import com.musiccut.core.plugin.effects.DeEsser
import com.musiccut.core.plugin.effects.DirectionMixer
import com.musiccut.core.plugin.effects.Distortion
import com.musiccut.core.plugin.effects.Enveloper
import com.musiccut.core.plugin.effects.Exciter
import com.musiccut.core.plugin.effects.Flanger
import com.musiccut.core.plugin.effects.GainPlugin
import com.musiccut.core.plugin.effects.Limiter
import com.musiccut.core.plugin.effects.Multipressor
import com.musiccut.core.plugin.effects.NoiseGate
import com.musiccut.core.plugin.effects.Overdrive
import com.musiccut.core.plugin.effects.Phaser
import com.musiccut.core.plugin.effects.PitchShifter
import com.musiccut.core.plugin.effects.SampleDelay
import com.musiccut.core.plugin.effects.StereoDelay
import com.musiccut.core.plugin.effects.StereoSpread
import com.musiccut.core.plugin.effects.TapeDelay
import com.musiccut.core.plugin.effects.ThreeBandEq
import com.musiccut.core.plugin.effects.Tremolo
import com.musiccut.core.project.ExternalPluginRef
import com.musiccut.core.project.PluginSlot
import com.musiccut.core.project.Project

/**
 * Catalog of all insert plugins. Third-party / user plugins can be added at runtime with
 * [register] (e.g. from a feature module) and immediately show up in the plugin browser.
 *
 * Imported Logic/AU slots are registered as transparent placeholders when no Android-native
 * implementation exists. Their metadata/state remains in [PluginSlot.external], which makes the
 * project round-trip safe even though an AU binary itself cannot run on Android.
 */
object PluginRegistry {
    private val builtIns = listOf(
        ChannelEq.DESCRIPTOR,
        ThreeBandEq.DESCRIPTOR,
        GraphicEq.GRAPHIC,
        GraphicEq.MATCH,
        Compressor.DESCRIPTOR,
        Limiter.DESCRIPTOR,
        AdaptiveLimiter.DESCRIPTOR,
        NoiseGate.DESCRIPTOR,
        DeEsser.DESCRIPTOR,
        Multipressor.DESCRIPTOR,
        Enveloper.DESCRIPTOR,
        ChromaVerb.DESCRIPTOR,
        SpaceDesigner.DESCRIPTOR,
        TapeDelay.DESCRIPTOR,
        DelayDesigner.DESCRIPTOR,
        StereoDelay.DESCRIPTOR,
        SampleDelay.DESCRIPTOR,
        Chorus.DESCRIPTOR,
        Flanger.DESCRIPTOR,
        Phaser.DESCRIPTOR,
        Tremolo.DESCRIPTOR,
        Ensemble.DESCRIPTOR,
        Ringshifter.DESCRIPTOR,
        Overdrive.DESCRIPTOR,
        Distortion.DESCRIPTOR,
        Bitcrusher.DESCRIPTOR,
        Exciter.DESCRIPTOR,
        ChromaGlow.DESCRIPTOR,
        AmpDesigner.DESCRIPTOR,
        AutoFilter.DESCRIPTOR,
        CutFilter.DESCRIPTOR,
        PitchShifter.DESCRIPTOR,
        PitchCorrection.DESCRIPTOR,
        VocalTransformer.DESCRIPTOR,
        DirectionMixer.DESCRIPTOR,
        StereoSpread.DESCRIPTOR,
        GainPlugin.DESCRIPTOR,
        LoudnessMeterPlugin.DESCRIPTOR,
        MultiMeter.DESCRIPTOR,
        TunerPlugin.DESCRIPTOR,
        TestOscillator.DESCRIPTOR,
    )

    private val extra = java.util.concurrent.CopyOnWriteArrayList<PluginDescriptor>()

    val all: List<PluginDescriptor> get() = builtIns + extra

    fun register(descriptor: PluginDescriptor) {
        require(all.none { it.id == descriptor.id }) { "Plugin id ${descriptor.id} already registered" }
        extra += descriptor
    }

    fun find(id: String): PluginDescriptor? = all.firstOrNull { it.id == id }

    fun create(id: String, sampleRate: Int): AudioPlugin? = find(id)?.factory?.invoke(sampleRate)

    /** Creates a slot, lazily restoring a preserved external plug-in placeholder if needed. */
    fun create(slot: PluginSlot, sampleRate: Int): AudioPlugin? {
        slot.external?.let { if (find(slot.pluginId) == null) registerExternal(slot.pluginId, it) }
        return create(slot.pluginId, sampleRate)
    }

    /** Idempotently registers a preserved external plug-in as a transparent Android placeholder. */
    fun registerExternal(pluginId: String, ref: ExternalPluginRef): PluginDescriptor {
        find(pluginId)?.let { return it }
        return synchronized(extra) {
            find(pluginId) ?: externalPluginDescriptor(pluginId, ref).also { extra += it }
        }
    }

    /** Restores all external descriptors after a project JSON has been reopened. */
    fun ensureExternal(project: Project) {
        val slots = buildList {
            addAll(project.masterPlugins)
            project.tracks.forEach { addAll(it.plugins) }
            project.buses.forEach { addAll(it.plugins) }
        }
        slots.forEach { slot -> slot.external?.let { registerExternal(slot.pluginId, it) } }
    }

    /**
     * Logic native plug-in type ids that have a MusicCut equivalent. Resolution by display name
     * keeps this table independent from MusicCut's internal ids.
     */
    fun logicNativePluginId(typeId: Int): String? {
        val logicName = LOGIC_NATIVE_NAMES[typeId] ?: return null
        return builtIns.firstOrNull { it.name.equals(logicName, ignoreCase = true) }?.id
    }

    fun byCategory(): Map<PluginCategory, List<PluginDescriptor>> =
        all.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })

    private val LOGIC_NATIVE_NAMES = mapOf(
        147 to "Tape Delay",
        154 to "Compressor",
        157 to "Enveloper",
        179 to "Noise Gate",
        183 to "Gain",
        193 to "Adaptive Limiter",
        194 to "Multipressor",
        199 to "Limiter",
        236 to "Channel EQ",
        248 to "Delay Designer",
        287 to "ChromaVerb",
    )
}
