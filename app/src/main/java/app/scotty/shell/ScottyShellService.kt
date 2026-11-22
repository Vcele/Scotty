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
        val rec = openSubmix(sampleRate) // already recording and verified to deliver data
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

        // Opening isn't enough: some devices accept a strategy but then never deliver audio. Each one
        // must start recording and produce data (silence counts) within 1.5 s.
        fun attempt(name: String, make: () -> AudioRecord): AudioRecord? = try {
            val r = make()
            when {
                r.state != AudioRecord.STATE_INITIALIZED -> {
                    r.release()
                    errors += "$name: not initialized"
                    null
                }
                else -> {
                    r.startRecording()
                    val probe = ByteArray(4096)
                    var got = 0
                    val deadline = System.currentTimeMillis() + 1500
                    while (got == 0 && System.currentTimeMillis() < deadline) {
                        val n = r.read(probe, 0, probe.size, AudioRecord.READ_NON_BLOCKING)
                        if (n < 0) break
                        got += n
                        if (n == 0) Thread.sleep(20)
                    }
                    if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING && got > 0) {
                        Log.i(TAG, "REMOTE_SUBMIX capturing via $name")
                        r
                    } else {
                        runCatching { r.stop() }
                        r.release()
                        errors += "$name: no audio data (state ${r.recordingState})"
                        null
                    }
                }
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

    private var p2pThread: android.os.HandlerThread? = null
    private var p2pChannel: android.net.wifi.p2p.WifiP2pManager.Channel? = null

    /**
     * Sets the Wi-Fi Display information element as a *source*. Strict sinks (Samsung TVs) ignore
     * Wi-Fi Direct requests from devices that don't advertise it. Android only lets holders of
     * NETWORK_SETTINGS / CONFIGURE_WIFI_DISPLAY set it – shell has NETWORK_SETTINGS.
     */
    @Synchronized
    override fun setWfdSource(enable: Boolean, controlPort: Int): String {
        installShellActivityThread()
        val ctx = shellContext()
        val mgr = ctx.getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java) ?: return "Wi-Fi Direct unavailable"
        val channel = p2pChannel ?: run {
            val t = android.os.HandlerThread("p2p").apply { start() }
            p2pThread = t
            mgr.initialize(ctx, t.looper, null).also { p2pChannel = it }
        } ?: return "Wi-Fi Direct channel unavailable"
        val info = android.net.wifi.p2p.WifiP2pWfdInfo().apply {
            setEnabled(enable)
            setDeviceType(android.net.wifi.p2p.WifiP2pWfdInfo.DEVICE_TYPE_WFD_SOURCE)
            setSessionAvailable(enable)
            setControlPort(controlPort)
            setMaxThroughput(50)
        }
        val result = java.util.concurrent.CompletableFuture<String>()
        return try {
            val m = android.net.wifi.p2p.WifiP2pManager::class.java.getMethod(
                "setWfdInfo", android.net.wifi.p2p.WifiP2pManager.Channel::class.java,
                android.net.wifi.p2p.WifiP2pWfdInfo::class.java, android.net.wifi.p2p.WifiP2pManager.ActionListener::class.java,
            )
            m.invoke(mgr, channel, info, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                override fun onSuccess() { result.complete("ok") }
                override fun onFailure(reason: Int) { result.complete("refused (reason $reason)") }
            })
            result.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Throwable) {
            "error: ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}"
        }.also { Log.i(TAG, "setWfdSource($enable) → $it") }
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
