package app.beamory.media

/** Receives finished 188-byte MPEG-TS packets. */
interface TsSink {
    fun onPacket(packet: ByteArray)
    /** Called right before the PAT/PMT + data of a video key frame are written. */
    fun onKeyFrameStart(pts90k: Long) {}
    /** Called after a complete access unit (video frame or audio frame) was written. */
    fun onAccessUnitEnd() {}
}

/**
 * Minimal MPEG-2 transport stream muxer: one program, H.264 video and/or ADTS AAC audio.
 * Used both for Miracast (TS over RTP) and for the AirPlay HLS stream.
 */
class TsMuxer(
    private val hasVideo: Boolean,
    private val hasAudio: Boolean,
    private val sink: TsSink,
) {
    private val cc = IntArray(0x2000)
    private var t0Us = Long.MIN_VALUE
    private var framesSincePsi = 0

    private fun to90k(us: Long): Long {
        if (t0Us == Long.MIN_VALUE) t0Us = us
        return ((us - t0Us).coerceAtLeast(0) * 9 / 100) + PTS_OFFSET
    }

    @Synchronized
    fun writeVideo(annexB: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (!hasVideo) return
        val pts = to90k(ptsUs)
        if (keyFrame) sink.onKeyFrameStart(pts)
        if (keyFrame || ++framesSincePsi >= 15) {
            writePsi()
            framesSincePsi = 0
        }
        // Access unit delimiter in front of every frame – some sinks insist on it.
        val payload = ByteArray(AUD.size + annexB.size)
        System.arraycopy(AUD, 0, payload, 0, AUD.size)
        System.arraycopy(annexB, 0, payload, AUD.size, annexB.size)
        val pcr = (pts - PCR_LAG) * 300
        writePes(VIDEO_PID, 0xE0, payload, pts, pcr, keyFrame, bounded = false)
        sink.onAccessUnitEnd()
    }

    @Synchronized
    fun writeAudio(adts: ByteArray, ptsUs: Long) {
        if (!hasAudio) return
        if (!hasVideo && framesSincePsi++ % 20 == 0) writePsi()
        val pts = to90k(ptsUs)
        val pcr = if (!hasVideo) (pts - PCR_LAG) * 300 else null
        writePes(AUDIO_PID, 0xC0, adts, pts, pcr, randomAccess = !hasVideo, bounded = true)
        sink.onAccessUnitEnd()
    }

    private fun writePsi() {
        writeSection(0x0000, buildPat())
        writeSection(PMT_PID, buildPmt())
    }

    private fun buildPat(): ByteArray {
        val s = byteArrayOf(
            0x00, // table_id
            0xB0.toByte(), 0x0D, // syntax indicator, length 13
            0x00, 0x01, // transport_stream_id
            0xC1.toByte(), 0x00, 0x00, // version 0, current, section 0/0
            0x00, 0x01, // program 1
            (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte(),
        )
        return withCrc(s)
    }

    private fun buildPmt(): ByteArray {
        val streams = ArrayList<Byte>()
        fun es(type: Int, pid: Int) {
            streams += type.toByte()
            streams += (0xE0 or (pid shr 8)).toByte()
            streams += (pid and 0xFF).toByte()
            streams += 0xF0.toByte(); streams += 0x00
        }
        if (hasVideo) es(0x1B, VIDEO_PID)
        if (hasAudio) es(0x0F, AUDIO_PID)
        val pcrPid = if (hasVideo) VIDEO_PID else AUDIO_PID
        val sectionLength = 9 + streams.size + 4
        val head = byteArrayOf(
            0x02,
            (0xB0 or (sectionLength shr 8)).toByte(), (sectionLength and 0xFF).toByte(),
            0x00, 0x01, // program number
            0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (pcrPid shr 8)).toByte(), (pcrPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00, // program_info_length 0
        )
        return withCrc(head + streams.toByteArray())
    }

    private fun withCrc(section: ByteArray): ByteArray {
        val crc = crc32(section)
        return section + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun writeSection(pid: Int, section: ByteArray) {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = (0x10 or nextCc(pid)).toByte()
        p[4] = 0x00 // pointer field
        System.arraycopy(section, 0, p, 5, section.size)
        sink.onPacket(p)
    }

    private fun writePes(
        pid: Int, streamId: Int, data: ByteArray, pts: Long, pcr: Long?, randomAccess: Boolean, bounded: Boolean,
    ) {
        val header = ByteArray(14)
        header[0] = 0; header[1] = 0; header[2] = 1; header[3] = streamId.toByte()
        val pesLen = if (bounded && data.size + 8 <= 0xFFFF) data.size + 8 else 0
        header[4] = (pesLen shr 8).toByte(); header[5] = pesLen.toByte()
        header[6] = 0x80.toByte(); header[7] = 0x80.toByte(); header[8] = 5
        header[9] = (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte()
        header[10] = (pts shr 22).toByte()
        header[11] = ((pts shr 14).toInt() and 0xFE or 1).toByte()
        header[12] = (pts shr 7).toByte()
        header[13] = ((pts shl 1).toInt() and 0xFE or 1).toByte()

        val full = header + data
        var off = 0
        var first = true
        while (off < full.size) {
            val p = ByteArray(188)
            p[0] = 0x47
            p[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = (pid and 0xFF).toByte()
            val remaining = full.size - off
            val withFields = first && (pcr != null || randomAccess)
            var af = if (withFields) 2 + (if (pcr != null) 6 else 0) else 0
            val payload = minOf(remaining, 184 - af)
            if (payload < 184 - af) af = 184 - payload
            p[3] = ((if (af > 0) 0x30 else 0x10) or nextCc(pid)).toByte()
            var i = 4
            if (af > 0) {
                p[i++] = (af - 1).toByte()
                if (af > 1) {
                    var flags = 0
                    if (withFields && randomAccess) flags = flags or 0x40
                    if (withFields && pcr != null) flags = flags or 0x10
                    p[i++] = flags.toByte()
                    if (withFields && pcr != null) {
                        val base = pcr / 300
                        val ext = (pcr % 300).toInt()
                        p[i++] = (base shr 25).toByte()
                        p[i++] = (base shr 17).toByte()
                        p[i++] = (base shr 9).toByte()
                        p[i++] = (base shr 1).toByte()
                        p[i++] = (((base and 1).toInt() shl 7) or 0x7E or (ext shr 8)).toByte()
                        p[i++] = ext.toByte()
                    }
                    while (i < 4 + af) p[i++] = 0xFF.toByte()
                }
            }
            System.arraycopy(full, off, p, 4 + af, payload)
            off += payload
            first = false
            sink.onPacket(p)
        }
    }

    private fun nextCc(pid: Int): Int {
        val v = cc[pid]
        cc[pid] = (v + 1) and 0x0F
        return v
    }

    companion object {
        const val PMT_PID = 0x100
        const val VIDEO_PID = 0x1011
        const val AUDIO_PID = 0x1100
        private const val PTS_OFFSET = 27_000L // 300 ms
        private const val PCR_LAG = 13_500L // 150 ms
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        private val CRC_TABLE = IntArray(256) { n ->
            var c = n shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }

        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            return crc
        }
    }
}
