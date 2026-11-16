package com.musiccut.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.GrowableAudioBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.max

/** Microphone recorder producing a float [AudioBuffer]. Caller must hold RECORD_AUDIO. */
class AudioRecorder(private val sampleRate: Int) {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    @Volatile private var running = false
    private var thread: Thread? = null
    private var result: GrowableAudioBuffer? = null

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBytes, 4096 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        val buf = GrowableAudioBuffer(sampleRate)
        result = buf
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = FloatArray(1024)
            record.startRecording()
            try {
                while (running) {
                    val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) {
                        buf.appendInterleaved(chunk, n, 1)
                        var pk = 0f
                        for (i in 0 until n) pk = max(pk, abs(chunk[i]))
                        _level.value = pk
                    }
                }
            } finally {
                record.stop()
                record.release()
                _level.value = 0f
            }
        }, "Recorder").also { it.start() }
    }

    /** Stops recording and returns the captured audio (or null if nothing was captured). */
    fun stop(): AudioBuffer? {
        if (!running) return null
        running = false
        thread?.join(1000)
        thread = null
        return result?.takeIf { it.frames > 0 }?.toBuffer().also { result = null }
    }
}
