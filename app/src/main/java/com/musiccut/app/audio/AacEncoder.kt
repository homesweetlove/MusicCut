package com.musiccut.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.musiccut.core.audio.AudioBuffer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Encodes an [AudioBuffer] to AAC-LC in an .m4a container using the platform encoder. */
object AacEncoder {
    fun encode(buffer: AudioBuffer, outFile: File, bitrate: Int = 256_000, progress: (Float) -> Unit = {}) {
        val sr = buffer.sampleRate
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        var framePos = 0
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val frames = minOf(inBuf.capacity() / 4, buffer.frames - framePos, 4096)
                        if (frames <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, framePos * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (i in 0 until frames) {
                                inBuf.putShort((buffer.left[framePos + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                                inBuf.putShort((buffer.right[framePos + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                            }
                            codec.queueInputBuffer(inIdx, 0, frames * 4, framePos * 1_000_000L / sr, 0)
                            framePos += frames
                            progress(framePos.toFloat() / buffer.frames)
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (muxing) muxer.stop()
            muxer.release()
        }
    }
}
