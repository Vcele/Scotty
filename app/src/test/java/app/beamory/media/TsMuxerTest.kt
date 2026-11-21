package app.beamory.media

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Muxes an ffmpeg-made H.264 + ADTS stream through [TsMuxer]. Set -Dbeamory.tsdir=<dir> containing
 * in.h264 and in.aac; writes out.ts there so it can be checked with ffprobe.
 */
class TsMuxerTest {
    @Test
    fun muxRealStreams() {
        val dir = System.getProperty("beamory.tsdir")?.let(::File)
        assumeTrue(dir != null && File(dir, "in.h264").exists())
        val out = File(dir, "out.ts").outputStream().buffered()
        val mux = TsMuxer(true, true, object : TsSink { override fun onPacket(packet: ByteArray) = out.write(packet) })
        val h264 = File(dir, "in.h264").readBytes()
        val aac = File(dir, "in.aac").readBytes()

        // split Annex-B into access units at AUD-less boundaries: group NALs until next slice start
        val nals = splitNals(h264)
        val frames = ArrayList<Pair<ByteArray, Boolean>>()
        var cur = java.io.ByteArrayOutputStream(); var key = false; var hasSlice = false
        for (n in nals) {
            val type = n[3].toInt() and 0x1F
            if ((type == 1 || type == 5 || type == 7) && hasSlice) { frames += cur.toByteArray() to key; cur = java.io.ByteArrayOutputStream(); key = false; hasSlice = false }
            if (type == 5) key = true
            if (type == 1 || type == 5) hasSlice = true
            if (type != 9) { cur.write(0); cur.write(n) }
        }
        if (hasSlice) frames += cur.toByteArray() to key

        val adts = ArrayList<ByteArray>()
        var i = 0
        while (i + 7 < aac.size) {
            val len = ((aac[i + 3].toInt() and 3) shl 11) or ((aac[i + 4].toInt() and 0xFF) shl 3) or ((aac[i + 5].toInt() and 0xFF) ushr 5)
            adts += aac.copyOfRange(i, i + len); i += len
        }
        var vi = 0; var ai = 0
        while (vi < frames.size || ai < adts.size) {
            val vt = vi * 1_000_000L / 30; val at = ai * 1024 * 1_000_000L / 48000
            if (vi < frames.size && (ai >= adts.size || vt <= at)) { mux.writeVideo(frames[vi].first, 1_000_000 + vt, frames[vi].second); vi++ }
            else { mux.writeAudio(adts[ai], 1_000_000 + at); ai++ }
        }
        out.close()
        println("frames=${frames.size} keys=${frames.count { it.second }} aac=${adts.size}")
    }

    private fun splitNals(b: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) { starts += i; i += 3 } else i++
        }
        return starts.mapIndexed { k, s -> b.copyOfRange(s, if (k + 1 < starts.size) starts[k + 1] else b.size) }
    }
}
