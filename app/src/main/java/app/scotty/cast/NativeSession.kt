package app.scotty.cast

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.projection.MediaProjection
import android.util.Log
import app.scotty.core.ScottyCore
import app.scotty.media.AacEncoder
import app.scotty.media.AudioCapture
import app.scotty.media.ScreenEncoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Glue between Android media APIs and a Rust core session: starts encoders when the core asks
 * for them (`mode`), pushes encoded media in, relays status/PIN/credentials, and reconnects
 * automatically if a live session drops.
 */
open class NativeSession(
    protected val ctx: Context,
    protected val projection: MediaProjection,
    private val dpi: Int,
    /** RAOP/AirPlay 2 audio wants raw PCM from the start instead of a `mode` event. */
    private val rawPcm: Boolean,
    /** Whether a dropped session should be re-established (not for Miracast). */
    private val reconnect: Boolean,
    private val credentialKey: String?,
    private val onEnded: (String?) -> Unit,
    private val starter: () -> Long,
) : CastSession {
    @Volatile protected var stopped = false
    @Volatile private var handle = -1L
    private var encoder: ScreenEncoder? = null
    private var capture: AudioCapture? = null
    private var aac: AacEncoder? = null
    private var wasLive = false
    private var retries = 0
    private val io = Executors.newSingleThreadExecutor()

    /** Runs on the session thread before events are processed; throw to fail the session. */
    protected open fun prepare() {}

    override fun start() {
        thread(name = "scotty-session") {
            handle = starter()
            if (handle < 0) {
                fail("Couldn't start the session")
                return@thread
            }
            try {
                prepare()
                if (rawPcm) startPcm()
            } catch (e: Exception) {
                Log.w(TAG, "prepare failed", e)
                fail(e.message ?: e.javaClass.simpleName)
                return@thread
            }
            while (!stopped) {
                val ev = ScottyCore.pollEvent(handle, 500) ?: continue
                if (!handleEvent(ev.split('\t'))) break
            }
        }
    }

    private fun handleEvent(e: List<String>): Boolean {
        when (e[0]) {
            "status" -> CastController.status(e.getOrElse(1) { "" })
            "live" -> {
                wasLive = true
                retries = 0
                CastController.status(e.getOrElse(1) { "" }, live = true)
            }
            "idr" -> encoder?.requestKeyFrame()
            "pin" -> CastController.pinRequested()
            "creds" -> credentialKey?.let { key -> e.getOrNull(1)?.let { Prefs.setCredentials(ctx, key, it) } }
            "mode" -> runCatching {
                startMedia(e[1].toInt(), e[2].toInt(), e[3].toInt(), e[4].toInt(), e[5].toInt(), e[6] == "1")
            }.onFailure {
                Log.e(TAG, "media start failed", it)
                fail("Couldn't start screen capture: ${it.message}")
                return false
            }
            "ended" -> {
                val err = e.getOrNull(1)?.takeIf { it.isNotEmpty() }
                if (stopped) return false
                ScottyCore.stop(handle)
                if (err != null && reconnect && wasLive && retries < MAX_RETRIES) {
                    retries++
                    CastController.status("Connection lost – reconnecting ($retries/$MAX_RETRIES)…")
                    Thread.sleep(1500L * retries)
                    if (stopped) return false
                    val h = starter()
                    if (h >= 0) {
                        handle = h
                        encoder?.requestKeyFrame()
                        return true
                    }
                }
                releaseMedia()
                stopped = true
                onEnded(err)
                return false
            }
        }
        return true
    }

    private fun startMedia(w: Int, h: Int, fps: Int, bitrate: Int, level: Int, audio: Boolean) {
        if (encoder != null) {
            encoder?.requestKeyFrame()
            return
        }
        val avcLevel = when (level) {
            40 -> CodecProfileLevel.AVCLevel4
            32 -> CodecProfileLevel.AVCLevel32
            else -> CodecProfileLevel.AVCLevel31
        }
        encoder = ScreenEncoder(projection, w, h, dpi, fps, bitrate, avcLevel) { data, pts, key ->
            ScottyCore.pushVideo(handle, data, pts, key)
        }.also { it.start() }
        if (audio) {
            val enc = AacEncoder(48_000) { frame, pts -> ScottyCore.pushAudio(handle, frame, pts) }
            aac = enc
            capture = runCatching {
                AudioCapture(projection, 48_000, 1024) { pcm, pts -> enc.encode(pcm, pts) }.also { it.start() }
            }.onFailure { Log.w(TAG, "audio capture unavailable", it) }.getOrNull()
        }
    }

    private fun startPcm() {
        capture = AudioCapture(projection, 44_100, 352) { pcm, pts -> ScottyCore.pushAudio(handle, pcm, pts) }
            .also { it.start() }
    }

    private fun releaseMedia() {
        capture?.stop(); capture = null
        aac?.release(); aac = null
        encoder?.stop(); encoder = null
    }

    protected fun fail(msg: String) {
        if (stopped) return
        stop()
        onEnded(msg)
    }

    override fun setVolume(volume: Float) {
        io.execute { if (handle >= 0 && !stopped) ScottyCore.setVolume(handle, volume) }
    }

    override fun submitPin(pin: String) {
        io.execute { if (handle >= 0) ScottyCore.submitPin(handle, pin) }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        releaseMedia()
        val h = handle
        io.execute { if (h >= 0) ScottyCore.stop(h) }
        io.shutdown()
    }

    companion object {
        private const val TAG = "NativeSession"
        private const val MAX_RETRIES = 3
    }
}

class AirPlayAudioSession(ctx: Context, projection: MediaProjection, target: CastTarget.AirPlayAudio, onEnded: (String?) -> Unit) :
    NativeSession(ctx, projection, 0, rawPcm = true, reconnect = true, credentialKey = target.name, onEnded = onEnded,
        starter = {
            ScottyCore.airplayAudioStart(target.host, target.port, Prefs.volume(ctx), target.protocol, Prefs.credentials(ctx, target.name))
        })

class AirPlayScreenSession(ctx: Context, projection: MediaProjection, dpi: Int, target: CastTarget.AirPlayScreen, onEnded: (String?) -> Unit) :
    NativeSession(ctx, projection, dpi, rawPcm = false, reconnect = true, credentialKey = target.name, onEnded = onEnded,
        starter = { ScottyCore.airplayScreenStart(target.host, target.port, Prefs.credentials(ctx, target.name)) })
