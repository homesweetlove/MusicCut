package com.musiccut.core.plugin

import com.musiccut.core.project.ExternalPluginRef

/**
 * Pass-through host used for plugins imported from another DAW that cannot execute on Android.
 *
 * The important part is that the slot is NOT dropped: its original identity and opaque state live
 * in [ExternalPluginRef] on the project model, so saving/reopening keeps the chain intact and a
 * future compatibility layer can remap or restore it.
 */
internal class ExternalPassthroughPlugin(
    sampleRate: Int,
    private val externalDescriptor: PluginDescriptor,
) : AudioPlugin(sampleRate) {
    override val descriptor: PluginDescriptor get() = externalDescriptor

    override fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        // Deliberately transparent. macOS Audio Units cannot be executed inside the Android host.
    }
}

internal fun externalPluginDescriptor(id: String, ref: ExternalPluginRef): PluginDescriptor {
    lateinit var descriptor: PluginDescriptor
    descriptor = PluginDescriptor(
        id = id,
        name = ref.name.ifBlank { "Unavailable Logic plug-in" },
        category = PluginCategory.UTILITY,
        description = buildString {
            append("Imported from ")
            append(ref.format)
            append(". The original plug-in is preserved but unavailable on this device")
            ref.fingerprint?.let { append(" · ").append(it) }
        },
        params = emptyList(),
        factory = { sr -> ExternalPassthroughPlugin(sr, descriptor) },
    )
    return descriptor
}
