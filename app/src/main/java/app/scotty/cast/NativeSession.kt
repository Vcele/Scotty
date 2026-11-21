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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioManager = ctx.getSystemService(android.media.AudioManager::class.java)
    private var nowPlaying: app.scotty.system.NowPlaying? = null
    @Volatile private var lastLoudMs = 0L
    @Volatile private var captureWarning = false
    private var lastTrackKey: String? = null

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
            if (rawPcm) main.post { startNowPlaying() }
            while (!stopped) {
                val ev = ScottyCore.pollEvent(handle, 500)
                if (rawPcm && wasLive) checkCapture()
                if (ev == null) continue
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
        lastLoudMs = System.currentTimeMillis()
        capture = AudioCapture(projection, 44_100, 352) { pcm, pts ->
            if (isAudible(pcm)) lastLoudMs = System.currentTimeMillis()
            ScottyCore.pushAudio(handle, pcm, pts)
        }.also { it.start() }
    }

    private fun isAudible(pcm: ByteArray): Boolean {
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            if (s > 64 || s < -64) return true
            i += 8 // every 2nd stereo frame is plenty
        }
        return false
    }

    /**
     * Apps can forbid capturing their audio. Android then hands us pure silence while the app
     * keeps playing on the phone. Detect that, explain it, and let the phone speaker play again.
     */
    private fun checkCapture() {
        val silentFor = System.currentTimeMillis() - lastLoudMs
        val musicPlaying = audioManager.isMusicActive
        if (!captureWarning && silentFor > 5000 && musicPlaying) {
            captureWarning = true
            val blocked = runCatching {
                audioManager.activePlaybackConfigurations.any {
                    it.audioAttributes.allowedCapturePolicy != android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL
                }
            }.getOrDefault(false)
            val app = nowPlaying?.currentPackage?.let { pkg ->
                runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            } ?: "The app that's playing"
            val msg = if (blocked) "$app doesn't allow other apps to capture its audio, so it keeps playing on this phone."
            else "No sound is reaching Scotty from $app. Try pausing and resuming playback."
            Log.w(TAG, "capture silent for ${silentFor}ms while music active (policy blocked=$blocked, app=$app)")
            CastController.warn(msg)
            CastController.captureBlockedListener?.invoke(true)
        } else if (captureWarning && silentFor < 1000) {
            captureWarning = false
            Log.i(TAG, "capture audible again")
            CastController.warn(null)
            CastController.captureBlockedListener?.invoke(false)
        }
    }

    private fun startNowPlaying() {
        if (stopped) return
        nowPlaying = app.scotty.system.NowPlaying(ctx) { t ->
            // Re-send on track change, and on play/pause/seek so progress stays right.
            lastTrackKey = t.key
            io.execute {
                if (handle >= 0 && !stopped) {
                    ScottyCore.setMetadata(handle, t.title, t.artist, t.album, t.artworkJpeg, "image/jpeg", t.durationMs, t.positionMs)
                }
            }
        }.also { if (!it.start()) Log.i(TAG, "no notification access – song info won't be shown on the speaker") }
    }

    private fun releaseMedia() {
        main.post { nowPlaying?.stop(); nowPlaying = null }
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
            ScottyCore.airplayAudioStart(target.host, target.port, target.ap2Port, Prefs.volume(ctx), target.protocol, Prefs.credentials(ctx, target.name))
        })

class AirPlayScreenSession(ctx: Context, projection: MediaProjection, dpi: Int, target: CastTarget.AirPlayScreen, onEnded: (String?) -> Unit) :
    NativeSession(ctx, projection, dpi, rawPcm = false, reconnect = true, credentialKey = target.name, onEnded = onEnded,
        starter = { ScottyCore.airplayScreenStart(target.host, target.port, Prefs.credentials(ctx, target.name)) })
