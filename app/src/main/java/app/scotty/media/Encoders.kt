package app.scotty.media

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "Encoders"

/** Mirrors the screen into an H.264 encoder and hands out Annex-B access units. */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val avcLevel: Int,
    private val onFrame: (data: ByteArray, ptsUs: Long, keyFrame: Boolean) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    @Volatile private var running = false

    fun start() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        fun format(withProfile: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (withProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
                setInteger(MediaFormat.KEY_LEVEL, avcLevel)
            }
        }
        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "profile/level rejected, falling back: ${e.message}")
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = codec.createInputSurface()
        codec.start()
        this.codec = codec
        running = true
        display = projection.createVirtualDisplay(
            "Scotty", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )
        thread(name = "video-out", isDaemon = true) { drain(codec) }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var csd: ByteArray? = null
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 20_000) } catch (e: IllegalStateException) { break }
            if (idx < 0) continue
            val buf = codec.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    csd = bytes
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val out = if (key && csd != null && firstNalType(bytes) != 7) csd + bytes else bytes
                    try {
                        onFrame(out, info.presentationTimeUs, key)
                    } catch (e: Exception) {
                        Log.w(TAG, "frame sink failed", e)
                    }
                }
            }
            runCatching { codec.releaseOutputBuffer(idx, false) }
        }
    }

    fun requestKeyFrame() {
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    fun stop() {
        running = false
        runCatching { display?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        display = null
        codec = null
    }

    private fun firstNalType(b: ByteArray): Int {
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                if (b[i + 2].toInt() == 1) return b[i + 3].toInt() and 0x1F
                if (b[i + 2].toInt() == 0 && i + 4 < b.size && b[i + 3].toInt() == 1) return b[i + 4].toInt() and 0x1F
            }
            i++
        }
        return -1
    }
}

/**
 * Captures what other apps play (Spotify, YouTube, games …) via AudioPlaybackCapture.
 * Delivers 16-bit little-endian interleaved stereo PCM chunks of [framesPerChunk] frames.
 */
class AudioCapture(
    private val projection: MediaProjection,
    val sampleRate: Int,
    private val framesPerChunk: Int,
    private val onPcm: (pcm: ByteArray, ptsUs: Long) -> Unit,
) {
    private var record: AudioRecord? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, framesPerChunk * 4 * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Audio capture unavailable (microphone permission?)" }
        rec.startRecording()
        record = rec
        running = true
        thread(name = "audio-capture", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            val chunkBytes = framesPerChunk * 4
            val chunkUs = framesPerChunk * 1_000_000L / sampleRate
            while (running) {
                val buf = ByteArray(chunkBytes)
                var n = 0
                while (n < chunkBytes && running) {
                    val r = rec.read(buf, n, chunkBytes - n)
                    if (r < 0) { running = false; break }
                    n += r
                }
                if (n == chunkBytes) onPcm(buf, System.nanoTime() / 1000 - chunkUs)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }
}

/** PCM → AAC-LC with ADTS headers (what MPEG-TS / HLS / Miracast want). */
class AacEncoder(
    private val sampleRate: Int,
    bitrate: Int = 160_000,
    private val onFrame: (adts: ByteArray, ptsUs: Long) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private val freqIndex = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
        .indexOf(sampleRate).also { require(it >= 0) }
    @Volatile private var released = false

    /** Feed PCM; called from the capture thread. */
    @Synchronized
    fun encode(pcm: ByteArray, ptsUs: Long) {
        if (released) return
        var off = 0
        var pts = ptsUs
        while (off < pcm.size) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(); continue }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            val n = minOf(buf.remaining(), pcm.size - off)
            buf.put(pcm, off, n)
            codec.queueInputBuffer(idx, 0, n, pts, 0)
            off += n
            pts += n / 4 * 1_000_000L / sampleRate
            drain()
        }
    }

    private fun drain() {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0)
            if (idx < 0) return
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val buf = codec.getOutputBuffer(idx)!!
                val frame = ByteArray(info.size + 7)
                buf.position(info.offset)
                buf.get(frame, 7, info.size)
                writeAdts(frame, frame.size)
                onFrame(frame, info.presentationTimeUs)
            }
            codec.releaseOutputBuffer(idx, false)
        }
    }

    private fun writeAdts(p: ByteArray, len: Int) {
        val profile = 2 // AAC LC
        val chan = 2
        p[0] = 0xFF.toByte()
        p[1] = 0xF1.toByte()
        p[2] = (((profile - 1) shl 6) + (freqIndex shl 2) + (chan shr 2)).toByte()
        p[3] = (((chan and 3) shl 6) + (len shr 11)).toByte()
        p[4] = ((len and 0x7FF) shr 3).toByte()
        p[5] = (((len and 7) shl 5) + 0x1F).toByte()
        p[6] = 0xFC.toByte()
    }

    @Synchronized
    fun release() {
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
