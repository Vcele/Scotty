package app.beamory.airplay

import android.media.projection.MediaProjection
import android.util.Log
import app.beamory.cast.CastController
import app.beamory.cast.CastSession
import app.beamory.cast.CastTarget
import app.beamory.media.AudioCapture
import app.beamory.net.RtspConnection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * AirPlay audio sender (RAOP). Streams everything the phone plays – Spotify included – as
 * lossless ALAC over RTP with NTP-style timing so the receiver stays in sync.
 *
 * Works with AirPlay 1 receivers and the many AirPlay 2 receivers that still accept RAOP
 * without HomeKit pairing (most TVs, Sonos, Denon/Marantz, Bose, shairport-sync, AirPort Express …).
 */
class RaopSession(
    private val projection: MediaProjection,
    private val target: CastTarget.AirPlayAudio,
    private val onEnded: (String?) -> Unit,
) : CastSession {
    @Volatile private var stopped = false
    private lateinit var rtsp: RtspConnection
    private var capture: AudioCapture? = null
    private val audioSock = DatagramSocket()
    private val controlSock = DatagramSocket()
    private val timingSock = DatagramSocket()
    private lateinit var remote: InetAddress
    private var serverPort = 0
    private var remoteControlPort = 0
    private var session: String? = null
    private lateinit var url: String

    private val ssrc = Random.nextInt()
    private var seq = Random.nextInt(0, 0xFFFF)
    private val rtpStart = Random.nextInt() and 0x7FFFFFFF
    @Volatile private var samplesSent = 0L
    private var startNs = 0L
    private var firstPacket = true
    private val history = arrayOfNulls<ByteArray>(1024)

    override fun start() {
        thread(name = "raop") {
            try {
                connect()
            } catch (e: Exception) {
                Log.w(TAG, "RAOP failed", e)
                if (!stopped) finish(e.message ?: "AirPlay connection failed")
            }
        }
    }

    private fun connect() {
        CastController.status("Connecting to ${target.name}…")
        val sock = Socket().apply { connect(InetSocketAddress(target.host, target.port), 5000); tcpNoDelay = true }
        remote = sock.inetAddress
        rtsp = RtspConnection(sock, userAgent = "iTunes/12.12.4 (Macintosh; OS X 12.6)").start()
        rtsp.onClosed = { if (!stopped) finish("${target.name} closed the connection") }
        val sid = Random.nextLong(1_000_000_000L, 9_999_999_999L)
        url = "rtsp://${rtsp.localAddress}/$sid"
        val clientId = "%016X".format(Random.nextLong())
        val base = mapOf(
            "Client-Instance" to clientId,
            "DACP-ID" to clientId,
            "Active-Remote" to Random.nextInt(1, Int.MAX_VALUE).toString(),
        )

        rtsp.request("OPTIONS", "*", base)

        val sdp = """
            v=0
            o=iTunes $sid 0 IN IP4 ${rtsp.localAddress}
            s=iTunes
            c=IN IP4 ${remote.hostAddress}
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 $FRAMES 0 16 40 10 14 2 255 0 0 $RATE
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val announce = rtsp.request("ANNOUNCE", url, base, sdp, contentType = "application/sdp")
        when (announce.status) {
            200 -> {}
            401 -> error("${target.name} is password protected – turn off “Require password” in its AirPlay settings")
            403 -> error("${target.name} refused the connection – allow “Everyone on the same network” in its AirPlay settings")
            453 -> error("${target.name} is busy with another AirPlay sender")
            470, 471 -> error("${target.name} requires AirPlay 2 HomeKit pairing, which Beamory can't do yet")
            else -> error("${target.name} rejected the stream (${announce.status})")
        }

        startTimingResponder()
        startControlListener()
        val setup = rtsp.request(
            "SETUP", url,
            base + mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=${controlSock.localPort};timing_port=${timingSock.localPort}",
            ),
        )
        check(setup.status == 200) { "${target.name} refused SETUP (${setup.status})" }
        session = setup.header("Session")?.substringBefore(';')
        val transport = setup.header("Transport").orEmpty()
        fun port(key: String) = Regex("$key=(\\d+)").find(transport)?.groupValues?.get(1)?.toInt()
        serverPort = port("server_port") ?: error("Receiver sent no audio port")
        remoteControlPort = port("control_port") ?: 0

        val record = rtsp.request(
            "RECORD", url,
            base + sessionHeader() + mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$rtpStart"),
        )
        check(record.status == 200) { "${target.name} refused RECORD (${record.status})" }
        sendVolume(volume)

        capture = AudioCapture(projection, RATE, FRAMES) { pcm, _ -> onPcm(pcm) }.also { it.start() }
        startSyncSender()
        CastController.status("Streaming lossless audio · play anything on your phone", live = true)

        // keep-alive
        while (!stopped && !rtsp.closed) {
            Thread.sleep(15_000)
            if (!stopped) runCatching { rtsp.request("OPTIONS", "*", base + sessionHeader(), timeoutMs = 5000) }
        }
    }

    private fun sessionHeader() = session?.let { mapOf("Session" to it) } ?: emptyMap()

    // ---- audio --------------------------------------------------------------------------

    private fun onPcm(pcm: ByteArray) {
        if (stopped) return
        val now = System.nanoTime()
        if (startNs == 0L) startNs = now
        // If capture stalled (nothing playing / system hiccup), pad with silence to keep the
        // RTP clock in step with real time.
        val expected = (now - startNs) * RATE / 1_000_000_000L
        while (expected - samplesSent > RATE / 4) sendFrame(SILENCE)
        sendFrame(pcm)
    }

    private fun sendFrame(pcm: ByteArray) {
        val alac = alacUncompressed(pcm)
        val p = ByteArray(12 + alac.size)
        val ts = (rtpStart + samplesSent).toInt()
        p[0] = 0x80.toByte()
        p[1] = (if (firstPacket) 0xE0 else 0x60).toByte()
        firstPacket = false
        p[2] = (seq shr 8).toByte(); p[3] = seq.toByte()
        putInt(p, 4, ts); putInt(p, 8, ssrc)
        System.arraycopy(alac, 0, p, 12, alac.size)
        history[seq and 1023] = p
        seq = (seq + 1) and 0xFFFF
        samplesSent += FRAMES
        runCatching { audioSock.send(DatagramPacket(p, p.size, remote, serverPort)) }
    }

    /** Wraps raw PCM in an ALAC frame with the "uncompressed" escape flag (no codec needed). */
    private fun alacUncompressed(pcm: ByteArray): ByteArray {
        val out = ByteArray(pcm.size + 8)
        var bit = 0
        fun put(v: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                if ((v shr i) and 1 == 1) out[bit ushr 3] = (out[bit ushr 3].toInt() or (0x80 ushr (bit and 7))).toByte()
                bit++
            }
        }
        put(1, 3) // CPE (stereo pair)
        put(0, 4) // element instance
        put(0, 12) // unused
        put(0, 1) // no explicit sample count (frame is exactly FRAMES long)
        put(0, 2) // bytes shifted
        put(1, 1) // escape: uncompressed
        var i = 0
        while (i < pcm.size) { // little-endian in, big-endian out
            put(pcm[i + 1].toInt() and 0xFF, 8)
            put(pcm[i].toInt() and 0xFF, 8)
            i += 2
        }
        put(7, 3) // ID_END
        return out.copyOf((bit + 7) / 8)
    }

    // ---- timing / sync / retransmit -------------------------------------------------------

    private fun startTimingResponder() = thread(name = "raop-timing", isDaemon = true) {
        val buf = ByteArray(128)
        while (!stopped) {
            val pkt = DatagramPacket(buf, buf.size)
            try { timingSock.receive(pkt) } catch (e: Exception) { break }
            if (pkt.length < 32 || (buf[1].toInt() and 0x7F) != 0x52) continue
            val recv = ntpNow()
            val r = ByteArray(32)
            r[0] = 0x80.toByte(); r[1] = 0xD3.toByte(); r[2] = 0; r[3] = 7
            System.arraycopy(buf, 24, r, 8, 8) // origin = their transmit time
            putLong(r, 16, recv)
            putLong(r, 24, ntpNow())
            runCatching { timingSock.send(DatagramPacket(r, r.size, pkt.socketAddress)) }
        }
    }

    private fun startControlListener() = thread(name = "raop-control", isDaemon = true) {
        val buf = ByteArray(64)
        while (!stopped) {
            val pkt = DatagramPacket(buf, buf.size)
            try { controlSock.receive(pkt) } catch (e: Exception) { break }
            if (pkt.length < 8 || (buf[1].toInt() and 0x7F) != 0x55) continue
            val first = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val count = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            for (k in 0 until count.coerceAtMost(64)) {
                val s = (first + k) and 0xFFFF
                val orig = history[s and 1023] ?: continue
                if ((((orig[2].toInt() and 0xFF) shl 8) or (orig[3].toInt() and 0xFF)) != s) continue
                val r = ByteArray(4 + orig.size)
                r[0] = 0x80.toByte(); r[1] = 0xD6.toByte(); r[2] = 0; r[3] = 1
                System.arraycopy(orig, 0, r, 4, orig.size)
                runCatching { controlSock.send(DatagramPacket(r, r.size, pkt.socketAddress)) }
            }
        }
    }

    private fun startSyncSender() = thread(name = "raop-sync", isDaemon = true) {
        var first = true
        while (!stopped) {
            if (remoteControlPort != 0) {
                val now = (rtpStart + samplesSent).toInt()
                val p = ByteArray(20)
                p[0] = (if (first) 0x90 else 0x80).toByte(); p[1] = 0xD4.toByte(); p[2] = 0; p[3] = 7
                putInt(p, 4, now - LATENCY)
                putLong(p, 8, ntpNow())
                putInt(p, 16, now)
                runCatching { controlSock.send(DatagramPacket(p, p.size, remote, remoteControlPort)) }
                first = false
            }
            Thread.sleep(1000)
        }
    }

    // ---- volume / lifecycle ---------------------------------------------------------------

    @Volatile private var volume = 0.8f

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (::rtsp.isInitialized && session != null) thread { runCatching { sendVolume(volume) } }
    }

    private fun sendVolume(v: Float) {
        val db = if (v <= 0.001f) -144.0 else -30.0 + 30.0 * v
        rtsp.request("SET_PARAMETER", url, sessionHeader(), "volume: %.6f\r\n".format(java.util.Locale.US, db), timeoutMs = 3000)
    }

    private fun finish(error: String?) {
        stop()
        onEnded(error)
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        capture?.stop()
        if (::rtsp.isInitialized) {
            thread {
                if (session != null) runCatching { rtsp.request("TEARDOWN", url, sessionHeader(), timeoutMs = 1500) }
                rtsp.close()
            }
        }
        audioSock.close(); controlSock.close(); timingSock.close()
    }

    companion object {
        private const val TAG = "Raop"
        private const val RATE = 44_100
        private const val FRAMES = 352
        private const val LATENCY = RATE * 2
        private val SILENCE = ByteArray(FRAMES * 4)

        fun ntpNow(): Long {
            val ms = System.currentTimeMillis()
            val secs = ms / 1000 + 2_208_988_800L
            val frac = ((ms % 1000) shl 32) / 1000
            return (secs shl 32) or frac
        }

        private fun putInt(b: ByteArray, o: Int, v: Int) {
            b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
        }

        private fun putLong(b: ByteArray, o: Int, v: Long) {
            putInt(b, o, (v shr 32).toInt()); putInt(b, o + 4, v.toInt())
        }

    }
}
