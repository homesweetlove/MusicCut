package com.musiccut.core.audio

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.OutputStream

/**
 * MP3 encoder (LAME, via the pure-Java jump3r port). Only the core encoder classes are used, so it
 * runs on Android where javax.sound is unavailable.
 */
object Mp3Encoder {
    fun encode(buffer: AudioBuffer, out: OutputStream, bitrateKbps: Int = 320, progress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }) {
        val lame = Lame()
        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val gfp: LameGlobalFlags = lame.lame_init()
        gfp.num_channels = 2
        gfp.in_samplerate = buffer.sampleRate
        gfp.out_samplerate = buffer.sampleRate
        gfp.mode = MPEGMode.JOINT_STEREO
        gfp.VBR = VbrMode.vbr_off
        gfp.brate = bitrateKbps
        gfp.quality = 2
        gfp.bWriteVbrTag = false
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        check(lame.lame_init_params(gfp) >= 0) { "MP3 encoder init failed" }

        val chunk = 1152 * 8
        val l = IntArray(chunk)
        val r = IntArray(chunk)
        val mp3 = ByteArray((1.25 * chunk + 7200).toInt())
        var pos = 0
        while (pos < buffer.frames) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val n = minOf(chunk, buffer.frames - pos)
            for (i in 0 until n) {
                l[i] = toInt32(buffer.left[pos + i])
                r[i] = toInt32(buffer.right[pos + i])
            }
            val bytes = lame.lame_encode_buffer_int(gfp, l, r, n, mp3, 0, mp3.size)
            check(bytes >= 0) { "MP3 encode error $bytes" }
            out.write(mp3, 0, bytes)
            pos += n
            progress(pos.toFloat() / buffer.frames)
        }
        val tail = lame.lame_encode_flush(gfp, mp3, 0, mp3.size)
        if (tail > 0) out.write(mp3, 0, tail)
        lame.lame_close(gfp)
        out.flush()
    }

    private fun toInt32(v: Float): Int = (v.coerceIn(-1f, 1f) * 2147483647.0).toInt()
}
