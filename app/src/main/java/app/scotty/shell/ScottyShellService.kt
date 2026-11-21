package app.scotty.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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
class ScottyShellService(private val given: Context?) : IScottyShell.Stub() {
    constructor() : this(null)

    @Volatile private var capturing = false
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
        val rec = openSubmix(sampleRate)
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("REMOTE_SUBMIX recording didn't start (state ${rec.recordingState})")
        }
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
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

    /**
     * Android 12+ checks the recorder's attribution: a shell process must present itself as
     * com.android.shell (uid 2000), or audio policy refuses REMOTE_SUBMIX. Strategies, newest first;
     * failures are collected so diagnostics show exactly what the device rejected.
     */
    @SuppressLint("MissingPermission")
    private fun openSubmix(rate: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(min, rate * 4 / 2)
        val errors = mutableListOf<String>()

        fun attempt(name: String, make: () -> AudioRecord): AudioRecord? = try {
            val r = make()
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "REMOTE_SUBMIX opened via $name")
                r
            } else {
                r.release()
                errors += "$name: not initialized"
                null
            }
        } catch (e: Throwable) {
            errors += "$name: ${e.javaClass.simpleName}: ${e.message}"
            null
        }

        installShellActivityThread()
        if (Build.VERSION.SDK_INT >= 31) {
            attempt("builder+shell context") {
                AudioRecord.Builder()
                    .setContext(shellContext())
                    .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }?.let { return it }
        }
        attempt("builder") {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }?.let { return it }
        @Suppress("DEPRECATION")
        attempt("legacy constructor") {
            AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX, rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        }?.let { return it }
        throw IllegalStateException("REMOTE_SUBMIX capture unavailable on SDK ${Build.VERSION.SDK_INT}: ${errors.joinToString("; ")}")
    }

    /** A context that identifies as the shell package (what scrcpy calls FakeContext). */
    private fun shellContext(): Context = object : ContextWrapper(systemContext() ?: given) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(2000 /* shell uid */).setPackageName(SHELL_PACKAGE).build()

        override fun getDeviceId(): Int = 0 // Context.DEVICE_ID_DEFAULT
    }

    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    }.getOrNull()

    /** Audio ops are attributed via the current ActivityThread; a bare shell process has none. */
    private fun installShellActivityThread() {
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            if (at.getMethod("currentActivityThread").invoke(null) != null) return
            val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        }.onFailure { Log.w(TAG, "ActivityThread setup failed", it) }
    }

    @Synchronized
    override fun stopCapture() {
        capturing = false
        captureThread?.join(1000)
        captureThread = null
    }

    companion object {
        private const val TAG = "ScottyShell"
        private const val SHELL_PACKAGE = "com.android.shell"
    }
}
