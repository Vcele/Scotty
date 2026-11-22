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
    protected val projection: MediaProjection?,
    private val dpi: Int,
    /** RAOP/AirPlay 2 audio wants raw PCM from the start instead of a `mode` event. */
    private val rawPcm: Boolean,
    /** Whether a dropped session should be re-established (not for Miracast). */
    private val reconnect: Boolean,
    private val credentialKey: String?,
    private val keyFrameInterval: Float = 1f,
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
    private var lastPlaying: Boolean? = null
    private var lastAnchor = 0L
    private val pendingVolume = java.util.concurrent.atomic.AtomicReference<Float?>(null)

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
                if (encoder != null) maybeRaiseBitrate()
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
                resumeAfterReconnect()
                CastController.status(e.getOrElse(1) { "" }, live = true)
            }
            "idr" -> encoder?.requestKeyFrame()
            "congestion" -> onCongestion()
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
                // Connection lost while music was beaming: pause the player first, so nothing ever
                // switches back to the phone speaker at full volume.
                if (err != null && wasLive && rawPcm) pauseForDrop()
                if (err != null && reconnect && wasLive && retries < MAX_RETRIES) {
                    retries++
                    CastController.status(
                        if (pausedByUs) "Connection lost – playback paused, reconnecting ($retries/$MAX_RETRIES)…"
                        else "Connection lost – reconnecting ($retries/$MAX_RETRIES)…",
                    )
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
                onEnded(if (err != null && pausedByUs) "Lost connection to the speaker – playback paused" else err)
                return false
            }
        }
        return true
    }


    @Volatile private var pausedByUs = false

    /** Pauses whatever is playing (media session first, media key as fallback). */
    private fun pauseForDrop() {
        if (pausedByUs) return
        val np = nowPlaying
        val playing = np?.isPlaying ?: audioManager.isMusicActive
        if (!playing) return
        if (np?.pause() != true) mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        pausedByUs = true
        Log.i(TAG, "connection lost – paused playback")
        Thread.sleep(400) // let the player stop before audio routing changes
    }

    private fun resumeAfterReconnect() {
        if (!pausedByUs) return
        pausedByUs = false
        if (nowPlaying?.play() != true) mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        Log.i(TAG, "reconnected – resumed playback")
    }

    private fun mediaKey(code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0))
    }

    private var maxBitrate = 0
    private var minBitrate = 1_000_000
    private var curBitrate = 0
    private var lastCongestionMs = 0L
    private var lastRaiseMs = 0L

    /** Network couldn't keep up: drop the video bitrate quickly. */
    private fun onCongestion() {
        val enc = encoder ?: return
        val next = maxOf(minBitrate, (curBitrate * 0.7).toInt())
        lastCongestionMs = System.currentTimeMillis()
        if (next != curBitrate) {
            curBitrate = next
            enc.setBitrate(next)
            ScottyCore.log(TAG, "congestion – video bitrate lowered to ${next / 1000} kbit/s")
        }
    }

    /** After 10 s without congestion, creep back up towards the mode's bitrate. */
    private fun maybeRaiseBitrate() {
        val enc = encoder ?: return
        val now = System.currentTimeMillis()
        if (curBitrate >= maxBitrate || now - lastCongestionMs < 8_000 || now - lastRaiseMs < 4_000) return
        curBitrate = minOf(maxBitrate, (curBitrate * 1.2).toInt())
        lastRaiseMs = now
        enc.setBitrate(curBitrate)
        ScottyCore.log(TAG, "link stable – video bitrate raised to ${curBitrate / 1000} kbit/s")
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
        val projection = projection ?: error("Screen capture permission missing")
        // Start below the mode's maximum; adaptive bitrate raises it when the link proves stable.
        maxBitrate = bitrate
        curBitrate = (bitrate * 0.5).toInt() // the Wi-Fi link ramps up after connecting; start gently
        lastCongestionMs = System.currentTimeMillis()
        minBitrate = if (h >= 1080) 1_500_000 else 1_000_000
        encoder = ScreenEncoder(projection, w, h, dpi, fps, curBitrate, avcLevel, keyFrameInterval) { data, pts, key ->
            ScottyCore.pushVideo(handle, data, pts, key)
        }.also { it.start() }
        ScottyCore.log(TAG, "video ${w}x$h@$fps, starting at ${curBitrate / 1000} kbit/s (max ${bitrate / 1000})")
        if (audio) {
            val enc = AacEncoder(48_000) { frame, pts -> ScottyCore.pushAudio(handle, frame, pts) }
            aac = enc
            // Power mode: capture the output as shell so the sound *moves* to the TV instead of
            // also playing on the phone.
            val pipe = if (app.scotty.shell.PowerMode.canCaptureDirect) app.scotty.shell.PowerMode.startCapture(48_000) else null
            if (pipe != null) {
                shellPipe = pipe
                ScottyCore.log(TAG, "screen audio via shell-level output capture")
                CastController.routingListener?.invoke(true)
                thread(name = "shell-aac", priority = Thread.MAX_PRIORITY) {
                    val buf = ByteArray(1024 * 4)
                    val chunkUs = 1024 * 1_000_000L / 48_000
                    runCatching {
                        java.io.FileInputStream(pipe.fileDescriptor).use { input ->
                            while (!stopped) {
                                var n = 0
                                while (n < buf.size) {
                                    val r = input.read(buf, n, buf.size - n)
                                    if (r < 0) return@use
                                    n += r
                                }
                                enc.encode(buf.copyOf(), System.nanoTime() / 1000 - chunkUs)
                            }
                        }
                    }.onFailure { Log.i(TAG, "shell screen audio ended: ${it.message}") }
                }
            } else {
                CastController.routingListener?.invoke(false)
                capture = runCatching {
                    AudioCapture(projection, 48_000, 1024) { pcm, pts -> enc.encode(pcm, pts) }.also { it.start() }
                }.onFailure { Log.w(TAG, "audio capture unavailable", it) }.getOrNull()
            }
        }
    }

    private var shellPipe: android.os.ParcelFileDescriptor? = null

    private fun startPcm() {
        lastLoudMs = System.currentTimeMillis()
        // Power mode (Shizuku): capture the whole output as shell. Android then routes media away from
        // the phone speaker, and apps that block normal capture are included.
        val pipe = if (app.scotty.shell.PowerMode.isReady || projection == null) app.scotty.shell.PowerMode.startCapture(44_100) else null
        if (pipe != null) {
            shellPipe = pipe
            Log.i(TAG, "using shell-level output capture")
            CastController.routingListener?.invoke(true)
            thread(name = "shell-pcm", priority = Thread.MAX_PRIORITY) {
                val buf = ByteArray(352 * 4)
                runCatching {
                    java.io.FileInputStream(pipe.fileDescriptor).use { input ->
                        while (!stopped) {
                            var n = 0
                            while (n < buf.size) {
                                val r = input.read(buf, n, buf.size - n)
                                if (r < 0) return@use
                                n += r
                            }
                            if (isAudible(buf)) lastLoudMs = System.currentTimeMillis()
                            ScottyCore.pushAudio(handle, buf.copyOf(), 0)
                        }
                    }
                }.onFailure { Log.i(TAG, "shell capture ended: ${it.message}") }
            }
            return
        }
        val p = projection ?: run {
            app.scotty.shell.PowerMode.markCaptureBroken("capture failed while starting a session")
            error("Power-mode capture didn't work on this device – tap the speaker again to use normal capture")
        }
        CastController.routingListener?.invoke(false)
        capture = AudioCapture(p, 44_100, 352) { pcm, pts ->
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
            // Players report position many times a second. Only forward a new track (with its cover)
            // or a play/pause/seek – receivers reset their display on every text update.
            val anchor = android.os.SystemClock.elapsedRealtime() - t.positionMs
            val newTrack = t.key != lastTrackKey
            val jumped = t.playing != lastPlaying || kotlin.math.abs(anchor - lastAnchor) > 2000
            if (!newTrack && !jumped) return@NowPlaying
            lastTrackKey = t.key
            lastPlaying = t.playing
            lastAnchor = anchor
            val art = if (newTrack) t.artworkJpeg else null
            CastController.lastMetadataInfo = "\"${t.title}\" – ${t.artist} (${t.album}), from ${t.packageName}, " +
                "cover ${t.artworkJpeg?.size ?: 0} bytes, ${if (t.playing) "playing" else "paused"} at ${t.positionMs / 1000}s"
            io.execute {
                if (handle >= 0 && !stopped) {
                    ScottyCore.setMetadata(handle, t.title, t.artist, t.album, art, "image/jpeg", t.durationMs, t.positionMs)
                }
            }
        }.also { if (!it.start()) Log.i(TAG, "no notification access – song info won't be shown on the speaker") }
    }

    private fun releaseMedia() {
        shellPipe?.let { p -> shellPipe = null; app.scotty.shell.PowerMode.stopCapture(); runCatching { p.close() } }
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

    /** Slider drags produce dozens of values a second; only the latest is sent. */
    override fun setVolume(volume: Float) {
        if (pendingVolume.getAndSet(volume) != null) return
        io.execute {
            val v = pendingVolume.getAndSet(null) ?: return@execute
            if (handle >= 0 && !stopped) ScottyCore.setVolume(handle, v)
        }
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
        private const val MIN_BITRATE = 1_500_000
    }
}

class AirPlayAudioSession(ctx: Context, projection: MediaProjection?, target: CastTarget.AirPlayAudio, onEnded: (String?) -> Unit) :
    NativeSession(ctx, projection, 0, rawPcm = true, reconnect = true, credentialKey = target.name, onEnded = onEnded,
        starter = {
            ScottyCore.airplayAudioStart(target.host, target.port, target.ap2Port, Prefs.volume(ctx), target.protocol, Prefs.credentials(ctx, target.name))
        })

class AirPlayScreenSession(ctx: Context, projection: MediaProjection, dpi: Int, target: CastTarget.AirPlayScreen, onEnded: (String?) -> Unit) :
    NativeSession(ctx, projection, dpi, rawPcm = false, reconnect = true, credentialKey = target.name, onEnded = onEnded,
        starter = { ScottyCore.airplayScreenStart(target.host, target.port, Prefs.credentials(ctx, target.name)) })
