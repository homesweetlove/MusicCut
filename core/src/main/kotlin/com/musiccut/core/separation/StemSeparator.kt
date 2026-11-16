package com.musiccut.core.separation

import com.musiccut.core.audio.AudioBuffer

data class Stems(val vocals: AudioBuffer, val instrumental: AudioBuffer)

enum class StemKind(val label: String) {
    VOCALS("Vocals"),
    DRUMS("Drums"),
    BASS("Bass"),
    OTHER("Other"),
    GUITAR("Guitar"),
    PIANO("Piano"),
}

/** A set of independently editable source-separation stems. */
data class MultiStems(val stems: Map<StemKind, AudioBuffer>) {
    operator fun get(kind: StemKind): AudioBuffer? = stems[kind]

    /**
     * Produces a karaoke/instrumental stem. When the original mix and vocals are available,
     * subtraction is preferred because it preserves anything a multi-stem model did not classify.
     */
    fun instrumental(reference: AudioBuffer? = null): AudioBuffer {
        val vocals = stems[StemKind.VOCALS]
        if (reference != null && vocals != null) {
            require(reference.sampleRate == vocals.sampleRate)
            val n = minOf(reference.frames, vocals.frames)
            return AudioBuffer(
                FloatArray(reference.frames) { i -> reference.left[i] - if (i < n) vocals.left[i] else 0f },
                FloatArray(reference.frames) { i -> reference.right[i] - if (i < n) vocals.right[i] else 0f },
                reference.sampleRate,
            )
        }
        val parts = stems.filterKeys { it != StemKind.VOCALS }.values.toList()
        require(parts.isNotEmpty()) { "No instrumental stems available" }
        val sr = parts.first().sampleRate
        require(parts.all { it.sampleRate == sr })
        val frames = parts.maxOf { it.frames }
        return AudioBuffer(
            FloatArray(frames) { i -> parts.sumOf { if (i < it.frames) it.left[i].toDouble() else 0.0 }.toFloat() },
            FloatArray(frames) { i -> parts.sumOf { if (i < it.frames) it.right[i].toDouble() else 0.0 }.toFloat() },
            sr,
        )
    }
}

enum class SeparationQuality(val label: String, val fftSize: Int) {
    FAST("Fast", 2048),
    BALANCED("Balanced", 4096),
    HIGH("High", 8192),
}

data class SeparationOptions(
    val quality: SeparationQuality = SeparationQuality.BALANCED,
    /** 0..1: how aggressively non-centered / non-vocal energy is rejected from the vocal stem. */
    val strength: Float = 0.6f,
    val vocalLowHz: Float = 120f,
    val vocalHighHz: Float = 11000f,
    /** 0..1: attenuation of drum-like transients in the vocal stem. */
    val percussionSuppression: Float = 0.5f,
)

/**
 * Splits a mix into vocal and instrumental stems. The built-in implementation is DSP based and
 * runs fully offline; an ML model (e.g. Demucs / Spleeter exported to ONNX or TFLite) can be
 * plugged in on the Android side by implementing this interface.
 */
interface StemSeparator {
    val name: String

    fun separate(
        input: AudioBuffer,
        options: SeparationOptions = SeparationOptions(),
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Stems
}

interface MultiStemSeparator {
    val name: String

    fun separate(
        input: AudioBuffer,
        requested: Set<StemKind> = StemKind.entries.toSet(),
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): MultiStems
}
