package com.musiccut.core.audio

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

enum class WavFormat(val bitsPerSample: Int, val isFloat: Boolean, val label: String) {
    PCM16(16, false, "16-bit PCM"),
    PCM24(24, false, "24-bit PCM"),
    FLOAT32(32, true, "32-bit Float"),
}

/** Minimal RIFF/WAVE reader & writer (PCM 8/16/24/32 and IEEE float 32/64). */
object WavCodec {

    fun write(buffer: AudioBuffer, out: OutputStream, format: WavFormat = WavFormat.PCM16) {
        val channels = 2
        val bytesPerSample = format.bitsPerSample / 8
        val dataSize = buffer.frames.toLong() * channels * bytesPerSample
        require(dataSize + 36 <= 0xFFFFFFFFL) { "WAV file too large" }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort((if (format.isFloat) 3 else 1).toShort())
        header.putShort(channels.toShort())
        header.putInt(buffer.sampleRate)
        header.putInt(buffer.sampleRate * channels * bytesPerSample)
        header.putShort((channels * bytesPerSample).toShort())
        header.putShort(format.bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())
        out.write(header.array())

        val chunkFrames = 4096
        val bb = ByteBuffer.allocate(chunkFrames * channels * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos < buffer.frames) {
            val n = minOf(chunkFrames, buffer.frames - pos)
            bb.clear()
            for (i in 0 until n) {
                writeSample(bb, buffer.left[pos + i], format)
                writeSample(bb, buffer.right[pos + i], format)
            }
            out.write(bb.array(), 0, bb.position())
            pos += n
        }
        out.flush()
    }

    private fun writeSample(bb: ByteBuffer, v: Float, format: WavFormat) {
        when (format) {
            WavFormat.FLOAT32 -> bb.putFloat(v)
            WavFormat.PCM16 -> bb.putShort((v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            WavFormat.PCM24 -> {
                val s = (v.coerceIn(-1f, 1f) * 8388607f).roundToInt()
                bb.put((s and 0xFF).toByte())
                bb.put(((s shr 8) and 0xFF).toByte())
                bb.put(((s shr 16) and 0xFF).toByte())
            }
        }
    }

    fun read(input: InputStream): AudioBuffer {
        val din = DataInputStream(BufferedInputStream(input))
        val riff = din.readTag()
        if (riff != "RIFF") throw IOException("Not a RIFF file")
        din.readIntLE()
        if (din.readTag() != "WAVE") throw IOException("Not a WAVE file")

        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        while (true) {
            val id = try {
                din.readTag()
            } catch (e: EOFException) {
                throw IOException("No data chunk")
            }
            val size = din.readIntLE().toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    formatTag = din.readShortLE()
                    channels = din.readShortLE()
                    sampleRate = din.readIntLE()
                    din.readIntLE()
                    din.readShortLE()
                    bits = din.readShortLE()
                    var remaining = size - 16
                    if (formatTag == 0xFFFE && remaining >= 10) {
                        // WAVE_FORMAT_EXTENSIBLE: cbSize, validBits, channelMask, subformat GUID
                        din.readShortLE(); din.readShortLE(); din.readIntLE()
                        formatTag = din.readShortLE()
                        remaining -= 10
                    }
                    din.skipFully(remaining + (size and 1))
                }
                "data" -> {
                    if (channels == 0) throw IOException("data before fmt")
                    val bytesPerSample = bits / 8
                    val frameBytes = bytesPerSample * channels
                    val frames = (size / frameBytes).toInt()
                    val l = FloatArray(frames)
                    val r = FloatArray(frames)
                    val raw = ByteArray(frameBytes * 4096)
                    var f = 0
                    while (f < frames) {
                        val n = minOf(4096, frames - f)
                        din.readFully(raw, 0, n * frameBytes)
                        val bb = ByteBuffer.wrap(raw, 0, n * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) {
                            val a = readSample(bb, formatTag, bits)
                            val b = if (channels > 1) readSample(bb, formatTag, bits) else a
                            for (c in 2 until channels) readSample(bb, formatTag, bits)
                            l[f + i] = a
                            r[f + i] = b
                        }
                        f += n
                    }
                    return AudioBuffer(l, r, sampleRate)
                }
                else -> din.skipFully(size + (size and 1))
            }
        }
    }

    private fun readSample(bb: ByteBuffer, tag: Int, bits: Int): Float = when {
        tag == 3 && bits == 32 -> bb.float
        tag == 3 && bits == 64 -> bb.double.toFloat()
        bits == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
        bits == 16 -> bb.short / 32768f
        bits == 24 -> {
            val b0 = bb.get().toInt() and 0xFF
            val b1 = bb.get().toInt() and 0xFF
            val b2 = bb.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
        }
        bits == 32 -> bb.int / 2147483648f
        else -> throw IOException("Unsupported WAV format tag=$tag bits=$bits")
    }

    private fun DataInputStream.readTag(): String {
        val b = ByteArray(4)
        readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun DataInputStream.readIntLE(): Int {
        val b = ByteArray(4)
        readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun DataInputStream.readShortLE(): Int {
        val b = ByteArray(2)
        readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else left -= s
        }
    }
}
