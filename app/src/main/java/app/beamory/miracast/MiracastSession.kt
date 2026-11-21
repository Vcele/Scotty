package app.beamory.miracast

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.projection.MediaProjection
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.beamory.cast.CastController
import app.beamory.cast.CastSession
import app.beamory.cast.CastTarget
import app.beamory.media.AacEncoder
import app.beamory.media.AudioCapture
import app.beamory.media.ScreenEncoder
import app.beamory.media.TsMuxer
import app.beamory.media.TsSink
import app.beamory.net.RtspConnection
import app.beamory.net.RtspMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Miracast (Wi-Fi Display R1) *source*, implemented entirely in user space:
 * Wi-Fi Direct group → RTSP capability negotiation (M1–M7) → H.264 + AAC in MPEG-TS over RTP.
 */
class MiracastSession(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val target: CastTarget.Miracast,
    private val onEnded: (error: String?) -> Unit,
) : CastSession {
    @Volatile private var stopped = false
    private var server: ServerSocket? = null
    private var source: WfdSource? = null
    private var receiver: BroadcastReceiver? = null

    override fun start() {
        thread(name = "miracast") {
            try {
                run()
            } catch (e: Exception) {
                Log.w(TAG, "session failed", e)
                if (!stopped) finish(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun run() {
        // Listen before connecting: the sink opens the RTSP connection as soon as the group is up.
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(WfdSource.RTSP_PORT))
            soTimeout = 1000
        }
        this.server = server
        P2p.tryAdvertiseWfdSource(ctx) { ok -> Log.i(TAG, "WFD source IE advertised: $ok") }

        CastController.status("Connecting over Wi-Fi Direct… accept the request on your TV if asked")
        val info = connectP2p()
        Log.i(TAG, "group formed, GO=${info.isGroupOwner} goAddr=${info.groupOwnerAddress}")
        CastController.status("Waiting for ${target.name} to start the session…")

        val deadline = System.currentTimeMillis() + 45_000
        var socket: java.net.Socket? = null
        while (socket == null && !stopped) {
            try {
                socket = server.accept()
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() > deadline) {
                    throw IllegalStateException("${target.name} connected over Wi-Fi Direct but never opened a Miracast session")
                }
            }
        }
        if (stopped || socket == null) return
        socket.tcpNoDelay = true
        val src = WfdSource(projection, socket, ctx.resources.displayMetrics.densityDpi) { err ->
            if (!stopped) finish(err)
        }
        source = src
        src.run()
    }

    @SuppressLint("MissingPermission")
    private fun connectP2p(): WifiP2pInfo {
        val (m, c) = P2p.get(ctx) ?: error("Wi-Fi Direct unavailable")
        val formed = CompletableFuture<WifiP2pInfo>()
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                m.requestConnectionInfo(c) { info -> if (info?.groupFormed == true) formed.complete(info) }
            }
        }
        receiver = rec
        ContextCompat.registerReceiver(
            ctx, rec, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        m.stopPeerDiscovery(c, null)
        val config = WifiP2pConfig().apply {
            deviceAddress = target.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 14 // we'd like to be GO; sink then gets its IP from us
        }
        fun attempt(retry: Boolean) {
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    if (retry && reason == WifiP2pManager.BUSY) {
                        m.cancelConnect(c, null)
                        m.removeGroup(c, null)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ attempt(false) }, 1500)
                    } else {
                        formed.completeExceptionally(IllegalStateException("Wi-Fi Direct connect failed (code $reason)"))
                    }
                }
            })
        }
        attempt(true)
        return try {
            formed.get(90, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("${target.name} didn't accept the Wi-Fi Direct connection")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun finish(error: String?) {
        stop()
        onEnded(error)
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        source?.stop()
        runCatching { server?.close() }
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        P2p.get(ctx)?.let { (m, c) -> m.removeGroup(c, null) }
    }

    companion object { private const val TAG = "Miracast" }
}

/** The RTSP/RTP part of a Wi-Fi Display source for one connected sink. */
class WfdSource(
    private val projection: MediaProjection,
    socket: java.net.Socket,
    private val dpi: Int,
    private val onEnded: (String?) -> Unit,
) {
    private data class Mode(val ceaBit: Int, val w: Int, val h: Int, val fps: Int, val wfdLevel: Int, val avcLevel: Int, val bitrate: Int)

    private val conn = RtspConnection(socket, onRequest = ::onRequest)
    private val sessionId = Random.nextInt(10_000_000, 99_999_999).toString()
    private val played = CompletableFuture<Unit>()
    @Volatile private var stopped = false

    private var mode = MODES[2]
    private var audio = false
    private var sinkRtpPort = 0
    private var encoder: ScreenEncoder? = null
    private var capture: AudioCapture? = null
    private var aac: AacEncoder? = null
    private var rtp: RtpTsSender? = null

    fun run() {
        conn.onClosed = { if (!stopped) { stop(); onEnded("The display closed the session") } }
        conn.start()

        // M1 – we ask the sink what it supports.
        val m1 = conn.request("OPTIONS", "*", mapOf("Require" to "org.wfa.wfd1.0"))
        check(m1.status == 200) { "Display rejected Miracast handshake (${m1.status})" }

        // M3 – capabilities
        val m3 = conn.request(
            "GET_PARAMETER", "rtsp://localhost/wfd1.0",
            body = "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\n",
        )
        check(m3.status == 200) { "Display didn't report its capabilities (${m3.status})" }
        val caps = m3.parameters()
        Log.i(TAG, "sink caps: $caps")
        chooseFormats(caps)
        val rtpPorts = caps["wfd_client_rtp_ports"] ?: "RTP/AVP/UDP;unicast 1028 0 mode=play"
        sinkRtpPort = rtpPorts.split(' ').getOrNull(1)?.toIntOrNull() ?: 1028

        // M4 – our choice
        val params = buildString {
            append("wfd_video_formats: 00 00 01 %02x %08x 00000000 00000000 00 0000 0000 00 none none\r\n"
                .format(mode.wfdLevel, 1 shl mode.ceaBit))
            if (audio) append("wfd_audio_codecs: AAC 00000001 00\r\n")
            append("wfd_presentation_URL: rtsp://${conn.localAddress}/wfd1.0/streamid=0 none\r\n")
            append("wfd_client_rtp_ports: $rtpPorts\r\n")
        }
        val m4 = conn.request("SET_PARAMETER", "rtsp://localhost/wfd1.0", body = params)
        check(m4.status == 200) { "Display refused the stream format (${m4.status})" }

        // M5 – ask the sink to SETUP; M6/M7 arrive as requests.
        CastController.status("Negotiating ${mode.w}×${mode.h} @ ${mode.fps} fps…")
        conn.request("SET_PARAMETER", "rtsp://localhost/wfd1.0", body = "wfd_trigger_method: SETUP\r\n")
        try {
            played.get(20, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("Display never started playback")
        }

        // Keep-alive (M16) while streaming.
        var ticks = 0
        while (!stopped && !conn.closed) {
            Thread.sleep(1000)
            if (++ticks % 25 == 0) {
                runCatching { conn.request("GET_PARAMETER", "rtsp://localhost/wfd1.0", mapOf("Session" to sessionId)) }
            }
        }
    }

    private fun chooseFormats(caps: Map<String, String>) {
        val video = caps["wfd_video_formats"]?.split(' ', ',')?.filter { it.isNotBlank() }
        if (video != null && video.size >= 5 && video[0] != "none") {
            val level = video[3].toIntOrNull(16) ?: 1
            val cea = video[4].toLongOrNull(16) ?: 1
            mode = MODES.firstOrNull { cea and (1L shl it.ceaBit) != 0L && level >= it.wfdLevel } ?: MODES.last()
        }
        audio = caps["wfd_audio_codecs"]?.contains("AAC") == true
    }

    private fun onRequest(req: RtspMessage, c: RtspConnection) {
        when (req.method) {
            "OPTIONS" -> c.respond(req, 200, mapOf(
                "Public" to "org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER",
            ))
            "SETUP" -> {
                val transport = req.header("Transport") ?: "RTP/AVP/UDP;unicast;client_port=$sinkRtpPort"
                Regex("client_port=(\\d+)").find(transport)?.let { sinkRtpPort = it.groupValues[1].toInt() }
                c.respond(req, 200, mapOf(
                    "Session" to "$sessionId;timeout=30",
                    "Transport" to "RTP/AVP/UDP;unicast;client_port=$sinkRtpPort;server_port=$SERVER_RTP_PORT-${SERVER_RTP_PORT + 1}",
                ))
            }
            "PLAY" -> {
                c.respond(req, 200, mapOf("Session" to sessionId, "Range" to "npt=now-"))
                thread { startStreaming(c.remoteAddress) }
            }
            "PAUSE" -> c.respond(req, 200, mapOf("Session" to sessionId))
            "TEARDOWN" -> {
                c.respond(req, 200, mapOf("Session" to sessionId))
                stop()
                onEnded(null)
            }
            "SET_PARAMETER" -> {
                if (req.bodyText.contains("wfd_idr_request")) encoder?.requestKeyFrame()
                c.respond(req, 200)
            }
            "GET_PARAMETER" -> c.respond(req, 200)
            else -> c.respond(req, 501)
        }
    }

    @Synchronized
    private fun startStreaming(sink: InetAddress) {
        if (rtp != null || stopped) { encoder?.requestKeyFrame(); return }
        try {
            val sender = RtpTsSender(InetSocketAddress(sink, sinkRtpPort))
            rtp = sender
            val mux = TsMuxer(hasVideo = true, hasAudio = audio, sink = sender)
            encoder = ScreenEncoder(projection, mode.w, mode.h, dpi, mode.fps, mode.bitrate, mode.avcLevel) { d, pts, key ->
                mux.writeVideo(d, pts, key)
            }.also { it.start() }
            if (audio) {
                val enc = AacEncoder(48_000) { f, pts -> mux.writeAudio(f, pts) }
                aac = enc
                capture = runCatching {
                    AudioCapture(projection, 48_000, 1024) { pcm, pts -> enc.encode(pcm, pts) }.also { it.start() }
                }.onFailure { Log.w(TAG, "audio capture failed", it) }.getOrNull()
            }
            played.complete(Unit)
            CastController.status("Mirroring ${mode.w}×${mode.h} @ ${mode.fps} fps" + if (audio) " with sound" else "", live = true)
        } catch (e: Exception) {
            Log.e(TAG, "stream start failed", e)
            played.completeExceptionally(e)
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        if (!conn.closed) thread { runCatching { conn.request("TEARDOWN", "rtsp://localhost/wfd1.0", mapOf("Session" to sessionId), timeoutMs = 1500) }; conn.close() }
        capture?.stop()
        aac?.release()
        encoder?.stop()
        rtp?.close()
    }

    companion object {
        private const val TAG = "WfdSource"
        const val RTSP_PORT = 7236
        private const val SERVER_RTP_PORT = 19000

        // WFD CEA resolution bits, best first. Level bitmap: 01=3.1 02=3.2 04=4 08=4.1 10=4.2
        private val MODES = listOf(
            Mode(7, 1920, 1080, 30, 0x04, CodecProfileLevel.AVCLevel4, 10_000_000),
            Mode(6, 1280, 720, 60, 0x02, CodecProfileLevel.AVCLevel32, 8_000_000),
            Mode(5, 1280, 720, 30, 0x01, CodecProfileLevel.AVCLevel31, 5_000_000),
            Mode(0, 640, 480, 60, 0x01, CodecProfileLevel.AVCLevel31, 2_500_000),
        )
    }
}

/** Packs TS packets 7 at a time into RTP (payload type 33, MP2T) over UDP. */
class RtpTsSender(private val dest: InetSocketAddress) : TsSink {
    private val socket = DatagramSocket()
    private val buf = ByteArray(12 + 7 * 188)
    private var count = 0
    private var seq = Random.nextInt(0, 0xFFFF)
    private val ssrc = Random.nextInt()

    override fun onPacket(packet: ByteArray) {
        System.arraycopy(packet, 0, buf, 12 + count * 188, 188)
        if (++count == 7) flush()
    }

    override fun onAccessUnitEnd() = flush()

    private fun flush() {
        if (count == 0) return
        val ts = (System.nanoTime() / 1000 * 9 / 100).toInt()
        buf[0] = 0x80.toByte(); buf[1] = 33
        buf[2] = (seq shr 8).toByte(); buf[3] = seq.toByte()
        buf[4] = (ts shr 24).toByte(); buf[5] = (ts shr 16).toByte(); buf[6] = (ts shr 8).toByte(); buf[7] = ts.toByte()
        buf[8] = (ssrc shr 24).toByte(); buf[9] = (ssrc shr 16).toByte(); buf[10] = (ssrc shr 8).toByte(); buf[11] = ssrc.toByte()
        seq = (seq + 1) and 0xFFFF
        try {
            socket.send(DatagramPacket(buf, 12 + count * 188, dest))
        } catch (e: Exception) {
            Log.w("RtpTs", "send: ${e.message}")
        }
        count = 0
    }

    fun close() = socket.close()
}
