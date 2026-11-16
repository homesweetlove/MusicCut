package com.musiccut.core.plugin

import com.musiccut.core.project.PluginSlot

/**
 * Real-time host for a list of [PluginSlot]s. Instances are kept alive across project edits
 * (matched by slot id) so reverb tails and filter states survive parameter tweaks.
 */
class PluginChain(private val sampleRate: Int) {
    @Volatile private var instances = LinkedHashMap<String, AudioPlugin>()
    private var active: List<Pair<PluginSlot, AudioPlugin>> = emptyList()
    private var lastSlots: List<PluginSlot>? = null

    fun sync(slots: List<PluginSlot>) {
        if (slots === lastSlots) return
        lastSlots = slots
        val next = LinkedHashMap<String, AudioPlugin>()
        val list = ArrayList<Pair<PluginSlot, AudioPlugin>>(slots.size)
        for (slot in slots) {
            val inst = instances[slot.id]?.takeIf { it.descriptor.id == slot.pluginId }
                ?: PluginRegistry.create(slot, sampleRate)
                ?: continue
            inst.setAll(slot.params)
            next[slot.id] = inst
            list += slot to inst
        }
        instances = next
        active = list
    }

    /** Project tempo handed to plugins (tempo-synced effects). */
    var tempo = 120f

    /** Supplies side-chain buffers for a track id (null = none). */
    var sidechainSource: ((String) -> Pair<FloatArray, FloatArray>?)? = null

    fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        for ((slot, plugin) in active) {
            if (slot.bypassed) continue
            plugin.hostTempo = tempo
            val key = slot.sidechainTrackId?.let { sidechainSource?.invoke(it) }
            plugin.sidechainL = key?.first
            plugin.sidechainR = key?.second
            plugin.run(left, right, offset, frames)
        }
    }

    /** Applies an automated parameter value (normalized 0..1) to a running plugin. */
    fun automate(slotId: String, paramId: String, normalized: Float) {
        val plugin = instances[slotId] ?: return
        val spec = plugin.descriptor.params.firstOrNull { it.id == paramId } ?: return
        plugin.setParam(paramId, spec.fromNormalized(normalized))
    }

    fun reset() = instances.values.forEach { it.reset() }

    /** Live plugin instance for a slot (UI metering / analyzers). */
    fun instance(slotId: String): AudioPlugin? = instances[slotId]
}
