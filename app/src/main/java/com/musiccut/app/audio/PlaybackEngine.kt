package com.musiccut.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.musiccut.core.engine.Metronome
import com.musiccut.core.engine.Mixer
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.project.Project
import com.musiccut.core.project.SourcePool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time playback: a dedicated high-priority thread pulls blocks from the [Mixer] and pushes
 * them to a float [AudioTrack]. The project reference is swapped atomically on every edit, so
 * changes (volume, plugins, edits) are heard immediately while playing.
 */
class PlaybackEngine(private val pool: SourcePool, val sampleRate: Int) {
    @Volatile var project: Project = Project(sampleRate = sampleRate)

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile private var running = false
    @Volatile private var seekTo = -1L
    @Volatile var stopAtEnd = true
    @Volatile var metronomeOn = false
    @Volatile var metronomeVolume = 0.5f
    /** Frame before which the metronome always clicks (count-in), even when [metronomeOn] is off. */
    @Volatile var countInUntil = Long.MIN_VALUE
    private var thread: Thread? = null
    private var mixer = Mixer(pool, sampleRate)
    private val metronome = Metronome(sampleRate)
    private var cursor = 0L

    fun meters(): Map<String, Float> = mixer.meters

    fun pluginInstance(slotId: String): AudioPlugin? = mixer.pluginInstance(slotId)

    /** Starts playback at the current position, or at [from] (may be negative for a count-in). */
    fun play(from: Long? = null) {
        if (running) return
        running = true
        _playing.value = true
        val start = from ?: _position.value
        thread = Thread({ loop(start) }, "AudioEngine").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        _playing.value = false
        mixer.meters.clear()
    }

    fun seek(frame: Long) {
        val f = frame.coerceAtLeast(0)
        if (running) seekTo = f
        _position.value = f
    }

    private fun loop(startFrame: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = 1024
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = maxOf(minBytes, block * 2 * 4 * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val latencyFrames = bufferBytes / 8
        val l = FloatArray(block)
        val r = FloatArray(block)
        val inter = FloatArray(block * 2)
        cursor = startFrame
        mixer.reset()
        metronome.reset()
        track.play()
        try {
            while (running) {
                val seek = seekTo
                if (seek >= 0) {
                    seekTo = -1
                    cursor = seek
                    mixer.reset()
                    track.pause(); track.flush(); track.play()
                }
                val p = project
                var n = block
                if (p.cycleEnabled && p.cycleEnd > p.cycleStart) {
                    if (cursor >= p.cycleEnd) cursor = p.cycleStart
                    if (cursor < p.cycleEnd) n = minOf(block.toLong(), p.cycleEnd - cursor).toInt()
                }
                mixer.render(p, cursor, n, l, r)
                if (metronomeOn || cursor < countInUntil) metronome.render(p, cursor, n, l, r, metronomeVolume)
                for (i in 0 until n) {
                    inter[2 * i] = l[i]
                    inter[2 * i + 1] = r[i]
                }
                track.write(inter, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                cursor += n
                _position.value = (cursor - latencyFrames).coerceAtLeast(0)
                if (stopAtEnd && !p.cycleEnabled && cursor > p.contentEnd + sampleRate && p.contentEnd > 0) {
                    running = false
                }
            }
        } finally {
            track.pause()
            track.flush()
            track.release()
            _playing.value = false
            running = false
        }
    }

    fun release() = stop()
}
