package app.beamory.airplay

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.projection.MediaProjection
import android.util.Log
import app.beamory.cast.CastController
import app.beamory.cast.CastSession
import app.beamory.cast.CastTarget
import app.beamory.media.AacEncoder
import app.beamory.media.AudioCapture
import app.beamory.media.ScreenEncoder
import app.beamory.media.TsMuxer
import app.beamory.media.TsSink
import app.beamory.net.RtspMessage
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Screen + sound to an AirPlay video receiver (Apple TV, AirPlay TVs).
 *
 * Real-time AirPlay *mirroring* is protected by Apple's FairPlay handshake, so instead Beamory
 * encodes the screen into a live HLS stream served from the phone and asks the receiver to play
 * it through the public AirPlay video API (`POST /play`). Expect ~3–5 s of latency – great
 * for videos and presentations, not for games.
 */
class AirPlayScreenSession(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val target: CastTarget.AirPlayScreen,
    private val onEnded: (String?) -> Unit,
) : CastSession {
    @Volatile private var stopped = false
    private val hls = HlsServer()
    private var encoder: ScreenEncoder? = null
    private var capture: AudioCapture? = null
    private var aac: AacEncoder? = null
    private var control: Socket? = null
    private val sessionId = UUID.randomUUID().toString().uppercase()

    override fun start() {
        thread(name = "airplay-video") {
            try {
                run()
            } catch (e: Exception) {
                Log.w(TAG, "failed", e)
                if (!stopped) finish(e.message ?: "AirPlay failed")
            }
        }
    }

    private fun run() {
        CastController.status("Connecting to ${target.name}…")
        val sock = Socket().apply { connect(InetSocketAddress(target.host, target.port), 5000) }
        control = sock
        val localIp = sock.localAddress.hostAddress
        hls.start()

        val mux = TsMuxer(hasVideo = true, hasAudio = true, sink = hls)
        encoder = ScreenEncoder(projection, 1280, 720, ctx.resources.displayMetrics.densityDpi, 30, 4_000_000,
            CodecProfileLevel.AVCLevel31) { d, pts, key -> mux.writeVideo(d, pts, key) }.also { it.start() }
        val enc = AacEncoder(48_000) { f, pts -> mux.writeAudio(f, pts) }
        aac = enc
        capture = runCatching {
            AudioCapture(projection, 48_000, 1024) { pcm, pts -> enc.encode(pcm, pts) }.also { it.start() }
        }.onFailure { Log.w(TAG, "no audio", it) }.getOrNull()

        CastController.status("Buffering…")
        hls.awaitSegments(3, 15_000)
        val playlist = "http://$localIp:${hls.port}/live.m3u8"
        Log.i(TAG, "playing $playlist")
        val body = "Content-Location: $playlist\nStart-Position: 0.000000\n"
        val resp = http(sock, "POST", "/play", body, "text/parameters")
        when (resp?.status) {
            200 -> {}
            401 -> error("${target.name} is password protected – turn off “Require password” in its AirPlay settings")
            403 -> error("${target.name} refused – set AirPlay access to “Everyone on the same network”")
            470, 471 -> error("${target.name} requires HomeKit pairing, which Beamory can't do yet")
            null -> error("${target.name} closed the connection")
            else -> error("${target.name} can't play the stream (${resp.status})")
        }
        CastController.status("Streaming screen + sound (a few seconds behind)", live = true)

        // Poll playback state; receivers end the session when this connection drops.
        while (!stopped) {
            Thread.sleep(3000)
            if (!stopped && http(sock, "GET", "/playback-info", null, null) == null) break
        }
        if (!stopped) finish(null)
    }

    private fun http(sock: Socket, method: String, path: String, body: String?, type: String?): RtspMessage? = try {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: ${target.host}:${target.port}\r\n")
            append("User-Agent: MediaControl/1.0\r\n")
            append("X-Apple-Session-ID: $sessionId\r\n")
            if (type != null) append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n\r\n")
        }
        val out = sock.getOutputStream()
        out.write(head.toByteArray()); out.write(bytes); out.flush()
        RtspMessage.read(input ?: BufferedInputStream(sock.getInputStream()).also { input = it })
    } catch (e: Exception) {
        Log.w(TAG, "$method $path: ${e.message}")
        null
    }

    private var input: BufferedInputStream? = null

    private fun finish(error: String?) {
        stop()
        onEnded(error)
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        control?.let { s -> thread { http(s, "POST", "/stop", null, null); runCatching { s.close() } } }
        capture?.stop()
        aac?.release()
        encoder?.stop()
        hls.stop()
    }

    companion object { private const val TAG = "AirPlayScreen" }
}

/** Tiny live-HLS server: cuts the TS stream into ~1 s segments at key frames. */
class HlsServer : TsSink {
    private class Segment(val index: Int, val data: ByteArray, val duration: Double)

    private val server = ServerSocket(0)
    val port get() = server.localPort
    private val segments = ArrayDeque<Segment>()
    private var current = ByteArrayOutputStream(512 * 1024)
    private var segStartPts = -1L
    private var nextIndex = 0
    private val lock = Object()
    @Volatile private var running = false

    fun start() {
        running = true
        thread(name = "hls-accept", isDaemon = true) {
            while (running) {
                val s = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    fun awaitSegments(n: Int, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (segments.size < n && System.currentTimeMillis() < end) lock.wait(250)
        }
    }

    override fun onPacket(packet: ByteArray) {
        current.write(packet)
    }

    override fun onKeyFrameStart(pts90k: Long) {
        if (segStartPts < 0) { segStartPts = pts90k; current.reset(); return }
        val dur = (pts90k - segStartPts) / 90_000.0
        if (dur < 0.9) return
        synchronized(lock) {
            segments.addLast(Segment(nextIndex++, current.toByteArray(), dur))
            while (segments.size > 8) segments.removeFirst()
            lock.notifyAll()
        }
        current = ByteArrayOutputStream(512 * 1024)
        segStartPts = pts90k
    }

    private fun playlist(): String = synchronized(lock) {
        val list = segments.toList().takeLast(5)
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n")
            append("#EXT-X-MEDIA-SEQUENCE:${list.firstOrNull()?.index ?: 0}\n")
            list.forEach { append("#EXTINF:%.3f,\nseg%d.ts\n".format(java.util.Locale.US, it.duration, it.index)) }
        }
    }

    private fun serve(s: Socket) = s.use {
        val req = RtspMessage.read(BufferedInputStream(s.getInputStream())) ?: return
        val path = req.startLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val (type, body) = when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl" to playlist().toByteArray()
            path.startsWith("/seg") && path.endsWith(".ts") -> {
                val idx = path.removePrefix("/seg").removeSuffix(".ts").toIntOrNull()
                val seg = synchronized(lock) { segments.firstOrNull { it.index == idx } }
                if (seg == null) null to null else "video/mp2t" to seg.data
            }
            else -> null to null
        }
        val out = s.getOutputStream()
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
            if (req.method != "HEAD") out.write(body)
        }
        out.flush()
    }
}
