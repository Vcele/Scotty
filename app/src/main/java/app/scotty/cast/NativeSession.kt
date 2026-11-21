package app.scotty.cast

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.projection.MediaProjection
import android.util.Log
import app.scotty.core.ScottyCore
import app.scotty.media.AacEncoder
import app.scotty.media.AudioCapture
import app.scotty.media.ScreenEncoder
import kotlin.concurrent.thread

/**
 * Glue between Android media APIs and a Rust core session: starts encoders when the core asks
 * for them (`mode` event), pushes encoded media in, and relays status to the UI.
 */
open class NativeSession(
    protected val projection: MediaProjection,
    private val dpi: Int,
    /** RAOP wants raw PCM from the start instead of a `mode` event. */
    private val rawPcm: Boolean,
    private val onEnded: (String?) -> Unit,
    private val starter: () -> Long,
) : CastSession {
    @Volatile protected var stopped = false
    @Volatile private var handle = -1L
    private var encoder: ScreenEncoder? = null
    private var capture: AudioCapture? = null
    private var aac: AacEncoder? = null

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
            "live" -> CastController.status(e.getOrElse(1) { "" }, live = true)
            "idr" -> encoder?.requestKeyFrame()
            "mode" -> runCatching {
                startMedia(e[1].toInt(), e[2].toInt(), e[3].toInt(), e[4].toInt(), e[5].toInt(), e[6] == "1")
            }.onFailure { Log.e(TAG, "media start failed", it); fail("Couldn't start screen capture: ${it.message}"); return false }
            "ended" -> {
                val err = e.getOrNull(1)?.takeIf { it.isNotEmpty() }
                if (!stopped) {
                    releaseMedia()
                    stopped = true
                    ScottyCore.stop(handle)
                    onEnded(err)
                }
                return false
            }
        }
        return true
    }

    private fun startMedia(w: Int, h: Int, fps: Int, bitrate: Int, level: Int, audio: Boolean) {
        if (encoder != null) return
        val avcLevel = when (level) {
            40 -> CodecProfileLevel.AVCLevel4
            32 -> CodecProfileLevel.AVCLevel32
            else -> CodecProfileLevel.AVCLevel31
        }
        val h0 = handle
        encoder = ScreenEncoder(projection, w, h, dpi, fps, bitrate, avcLevel) { data, pts, key ->
            ScottyCore.pushVideo(h0, data, pts, key)
        }.also { it.start() }
        if (audio) {
            val enc = AacEncoder(48_000) { frame, pts -> ScottyCore.pushAudio(h0, frame, pts) }
            aac = enc
            capture = runCatching {
                AudioCapture(projection, 48_000, 1024) { pcm, pts -> enc.encode(pcm, pts) }.also { it.start() }
            }.onFailure { Log.w(TAG, "audio capture unavailable", it) }.getOrNull()
        }
    }

    private fun startPcm() {
        val h0 = handle
        capture = AudioCapture(projection, 44_100, 352) { pcm, pts -> ScottyCore.pushAudio(h0, pcm, pts) }
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
        if (handle >= 0) ScottyCore.setVolume(handle, volume)
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        releaseMedia()
        if (handle >= 0) ScottyCore.stop(handle)
    }

    companion object { private const val TAG = "NativeSession" }
}

class AirPlayAudioSession(projection: MediaProjection, target: CastTarget.AirPlayAudio, volume: Float, onEnded: (String?) -> Unit) :
    NativeSession(projection, 0, rawPcm = true, onEnded = onEnded,
        starter = { ScottyCore.airplayAudioStart(target.host, target.port, volume) })

class AirPlayScreenSession(projection: MediaProjection, dpi: Int, target: CastTarget.AirPlayScreen, onEnded: (String?) -> Unit) :
    NativeSession(projection, dpi, rawPcm = false, onEnded = onEnded,
        starter = { ScottyCore.airplayScreenStart(target.host, target.port) })
