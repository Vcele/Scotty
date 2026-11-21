package app.scotty.shell

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Lives in a separate process that Shizuku starts with shell (ADB) privileges. Shell holds
 * CAPTURE_AUDIO_OUTPUT, so it can record the REMOTE_SUBMIX output: Android then sends media there
 * *instead of* the phone speaker, and apps that forbid playback capture are included too.
 * (Same mechanism scrcpy uses for its "output" audio source.)
 */
class ScottyShellService() : IScottyShell.Stub() {
    @Suppress("unused") // Shizuku passes a Context on newer versions
    constructor(context: Context) : this()

    @Volatile private var capturing = false
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null

    override fun destroy() {
        stopCapture()
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return "${p.waitFor()}\n$out"
    }

    @Synchronized
    override fun startCapture(sampleRate: Int): ParcelFileDescriptor {
        stopCapture()
        attributeToShell()
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.REMOTE_SUBMIX, sampleRate,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate * 4 / 2),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "REMOTE_SUBMIX capture unavailable" }
        rec.startRecording()
        record = rec
        capturing = true
        captureThread = thread(name = "submix", priority = Thread.MAX_PRIORITY) {
            val buf = ByteArray(352 * 4)
            try {
                FileOutputStream(writeSide.fileDescriptor).use { out ->
                    while (capturing) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) break
                        if (n > 0) out.write(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "capture pipe closed: ${e.message}")
            } finally {
                runCatching { writeSide.close() }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                capturing = false
            }
        }
        return readSide
    }

    @Synchronized
    override fun stopCapture() {
        capturing = false
        captureThread?.join(1000)
        captureThread = null
        record = null
    }

    /**
     * Audio ops are attributed via the current ActivityThread; a bare shell process has none, so
     * create one marked as system thread (scrcpy does the same).
     */
    private fun attributeToShell() {
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            if (at.getMethod("currentActivityThread").invoke(null) != null) return
            val ctor = at.getDeclaredConstructor().apply { isAccessible = true }
            val thread = ctor.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        }.onFailure { Log.w(TAG, "attribution setup failed", it) }
    }

    companion object { private const val TAG = "ScottyShell" }
}
