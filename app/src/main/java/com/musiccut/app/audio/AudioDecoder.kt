package com.musiccut.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.GrowableAudioBuffer
import com.musiccut.core.audio.Resampler
import java.io.IOException
import java.nio.ByteOrder

/** Decodes any audio format supported by the device (mp3, aac/m4a, wav, flac, ogg, …) to float PCM. */
object AudioDecoder {

    fun decode(context: Context, uri: Uri, targetRate: Int, progress: (Float) -> Unit = {}): AudioBuffer {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track found")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = GrowableAudioBuffer(rate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var scratch = FloatArray(0)
            try {
                while (!outputDone) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val count = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> info.size / 4
                                else -> info.size / 2
                            }
                            if (scratch.size < count) scratch = FloatArray(count)
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                buf.asFloatBuffer().get(scratch, 0, count)
                            } else {
                                val sb = buf.asShortBuffer()
                                for (i in 0 until count) scratch[i] = sb.get(i) / 32768f
                            }
                            out.appendInterleaved(scratch, count, channels)
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f) * 0.8f)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            val decoded = out.toBuffer().let { AudioBuffer(it.left, it.right, rate) }
            return Resampler.resample(decoded, targetRate) { progress(0.8f + it * 0.2f) }
        } finally {
            extractor.release()
        }
    }
}
