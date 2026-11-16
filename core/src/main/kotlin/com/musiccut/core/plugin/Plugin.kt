package com.musiccut.core.plugin

import kotlin.math.ln
import kotlin.math.exp

enum class PluginCategory(val label: String) {
    EQ("EQ"),
    DYNAMICS("Dynamics"),
    REVERB("Reverb"),
    DELAY("Delay"),
    MODULATION("Modulation"),
    DISTORTION("Distortion"),
    FILTER("Filter"),
    PITCH("Pitch"),
    IMAGING("Imaging"),
    UTILITY("Utility"),
}

enum class ParamScale { LINEAR, LOG }

/**
 * Describes one automatable plugin parameter. When [choices] is set the parameter is discrete and
 * the value is the choice index.
 */
data class ParamSpec(
    val id: String,
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val scale: ParamScale = ParamScale.LINEAR,
    val choices: List<String>? = null,
) {
    val isDiscrete: Boolean get() = choices != null

    /** Maps a 0..1 UI position to a parameter value. */
    fun fromNormalized(n: Float): Float {
        val t = n.coerceIn(0f, 1f)
        if (choices != null) return kotlin.math.round(min + t * (max - min))
        return when (scale) {
            ParamScale.LINEAR -> min + t * (max - min)
            ParamScale.LOG -> exp(ln(min) + t * (ln(max) - ln(min)))
        }
    }

    fun toNormalized(v: Float): Float {
        val c = v.coerceIn(min, max)
        if (max == min) return 0f
        return when (scale) {
            ParamScale.LINEAR -> (c - min) / (max - min)
            ParamScale.LOG -> (ln(c) - ln(min)) / (ln(max) - ln(min))
        }
    }

    fun format(v: Float): String {
        choices?.let { return it.getOrElse(v.toInt()) { "?" } }
        val num = when {
            kotlin.math.abs(v) >= 1000 && unit == "Hz" -> String.format("%.2fk", v / 1000f)
            kotlin.math.abs(v) >= 100 -> String.format("%.0f", v)
            kotlin.math.abs(v) >= 10 -> String.format("%.1f", v)
            else -> String.format("%.2f", v)
        }
        return if (unit.isEmpty()) num else "$num $unit"
    }
}

data class PluginDescriptor(
    val id: String,
    val name: String,
    val category: PluginCategory,
    val description: String,
    val params: List<ParamSpec>,
    val presets: Map<String, Map<String, Float>> = emptyMap(),
    val factory: (sampleRate: Int) -> AudioPlugin,
) {
    fun defaults(): Map<String, Float> = params.associate { it.id to it.default }
}

/**
 * Base class for every insert effect. Implementations process non-interleaved stereo in place and
 * must be real-time safe (no allocation in [process]).
 */
abstract class AudioPlugin(val sampleRate: Int) {
    abstract val descriptor: PluginDescriptor

    private val values: FloatArray by lazy { FloatArray(descriptor.params.size) { descriptor.params[it].default } }
    private val index: Map<String, Int> by lazy { descriptor.params.withIndex().associate { it.value.id to it.index } }
    @Volatile private var dirty = true

    fun setParam(id: String, value: Float) {
        val i = index[id] ?: return
        val spec = descriptor.params[i]
        val v = value.coerceIn(spec.min, spec.max)
        if (values[i] != v) {
            values[i] = v
            dirty = true
        }
    }

    fun param(id: String): Float = values[index[id] ?: error("Unknown param $id in ${descriptor.id}")]

    fun setAll(params: Map<String, Float>) = params.forEach { (k, v) -> setParam(k, v) }

    /**
     * Current gain reduction in dB (<= 0) for dynamics processors, read by the UI for GR meters.
     * Null for plugins that don't reduce gain.
     */
    open val gainReductionDb: Float? get() = null

    /** Live values shown by metering plugins (Loudness Meter, Tuner, MultiMeter…). */
    open val readouts: Map<String, Float> get() = emptyMap()

    /** Project tempo, set by the host every block (for tempo-synced delays). */
    @Volatile var hostTempo: Float = 120f

    /**
     * Side-chain key signal for the current block (same offset/length as [run]'s buffers), or null
     * to key from the plugin's own input.
     */
    var sidechainL: FloatArray? = null
    var sidechainR: FloatArray? = null

    /** Output tap for spectrum analyzers (EQ plugins); null if the plugin has none. */
    open val analyzer: AnalyzerTap? get() = null

    /** Latency introduced by the plugin in samples (for look-ahead processors). */
    open val latency: Int get() = 0

    fun run(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        if (dirty) {
            dirty = false
            update()
        }
        process(left, right, offset, frames)
    }

    /** Called on the audio thread before processing whenever any parameter changed. */
    protected open fun update() {}

    protected abstract fun process(left: FloatArray, right: FloatArray, offset: Int, frames: Int)

    open fun reset() {}
}

/** Utility for dry/wet mixes; [mix] in percent. */
internal fun mixWet(dry: Float, wet: Float, mixPercent: Float): Float {
    val m = mixPercent * 0.01f
    return dry * (1f - m) + wet * m
}

/** Lock-free ring buffer of recent mono output samples for spectrum display. */
class AnalyzerTap(val size: Int = 4096) {
    private val buf = FloatArray(size)
    @Volatile private var pos = 0

    fun push(left: FloatArray, right: FloatArray, offset: Int, frames: Int) {
        var p = pos
        for (i in offset until offset + frames) {
            buf[p] = (left[i] + right[i]) * 0.5f
            p = if (p + 1 == size) 0 else p + 1
        }
        pos = p
    }

    /** Copies the most recent [out].size samples in chronological order. */
    fun snapshot(out: FloatArray) {
        val n = minOf(out.size, size)
        val end = pos
        for (i in 0 until n) out[i] = buf[(end - n + i + size) % size]
    }
}
