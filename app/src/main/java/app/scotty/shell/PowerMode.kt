package app.scotty.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import app.scotty.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Optional "power mode" through Shizuku:
 *  - a one-time setup that permanently grants what normally needs repeated prompts, and
 *  - while Shizuku runs, shell-level audio capture (true rerouting, includes apps that block capture).
 * Without Shizuku everything falls back to the normal MediaProjection path.
 */
object PowerMode {
    enum class Status {
        /** Shizuku isn't installed or not running. */
        NoShizuku,
        /** Shizuku runs, but Scotty hasn't been allowed to use it yet. */
        NeedsPermission,
        /** Allowed; the shell helper is starting. */
        Connecting,
        /** Connected to the shell helper. */
        Ready,
    }

    private val _status = MutableStateFlow(Status.NoShizuku)
    val status: StateFlow<Status> = _status

    @Volatile var service: IScottyShell? = null
        private set
    private var app: Context? = null
    private var bindLatch = CountDownLatch(1)
    private const val REQUEST_CODE = 7236

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ScottyShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IScottyShell.Stub.asInterface(binder)
                Log.i(TAG, "shell helper connected")
                kotlin.concurrent.thread(name = "capture-probe") { probeCapture() }
            }
            refresh()
            bindLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            refresh()
        }
    }

    /** Call from every entry point (activities, tile, service); cheap after the first call. */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh(); bindIfAllowed() }
        Shizuku.addBinderDeadListener { service = null; refresh() }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == REQUEST_CODE && result == PackageManager.PERMISSION_GRANTED) bindIfAllowed()
            refresh()
        }
    }

    private fun shizukuAlive() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun permitted() = shizukuAlive() && !Shizuku.isPreV11() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun refresh() {
        _status.value = when {
            !shizukuAlive() -> Status.NoShizuku
            !permitted() -> Status.NeedsPermission
            service != null -> Status.Ready
            else -> {
                bindIfAllowed()
                if (service != null) Status.Ready else Status.Connecting
            }
        }
    }

    fun requestPermission() {
        if (shizukuAlive()) Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun bindIfAllowed() {
        if (!permitted() || service != null) return
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { Log.w(TAG, "bind failed", it) }
    }

    /** Blocks briefly (off the main thread) until the helper is connected, if Shizuku allows it. */
    fun awaitService(timeoutMs: Long = 3000): IScottyShell? {
        service?.let { return it }
        if (!permitted()) return null
        bindLatch = CountDownLatch(1)
        bindIfAllowed()
        bindLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return service
    }

    val isReady get() = service != null

    /** null = not probed yet; false = this device refused shell capture (see [captureError]). */
    @Volatile var captureVerified: Boolean? = null
        private set
    @Volatile var captureError: String? = null
        private set

    /** Power mode may skip MediaProjection only once shell capture is known to work here. */
    val canCaptureDirect get() = service != null && captureVerified == true

    /** Opens and closes the shell capture once, so a broken device never loses the normal path. */
    fun probeCapture() {
        val s = service ?: return
        runCatching {
            s.startCapture(44_100).close()
            s.stopCapture()
        }.onSuccess {
            captureVerified = true
            captureError = null
            Log.i(TAG, "shell capture probe ok")
        }.onFailure {
            markCaptureBroken(it.message ?: it.javaClass.simpleName)
        }
        refresh()
    }

    fun markCaptureBroken(reason: String) {
        captureVerified = false
        captureError = reason
        Log.w(TAG, "shell capture unavailable: $reason")
    }

    /**
     * Grants that persist across reboots, so they only ever need to be done once:
     * no screen-capture prompt, runtime permissions, song-info access (even for sideloaded installs
     * blocked by "restricted settings"), and no battery-optimisation kills.
     */
    fun applyPermanentGrants(): List<Pair<String, Boolean>> {
        val s = awaitService() ?: return listOf("Shizuku helper not available" to false)
        val pkg = BuildConfig.APPLICATION_ID
        val steps = listOf(
            "Skip the screen-capture prompt" to "appops set $pkg PROJECT_MEDIA allow",
            "Allow restricted settings" to "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow",
            "Audio capture permission" to "pm grant $pkg android.permission.RECORD_AUDIO",
            "Notifications" to "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "Nearby devices (Miracast)" to "pm grant $pkg android.permission.NEARBY_WIFI_DEVICES",
            "Song info access" to "cmd notification allow_listener $pkg/app.scotty.system.NowPlayingListener",
            "No battery optimisation" to "dumpsys deviceidle whitelist +$pkg",
        )
        return steps.map { (label, cmd) ->
            val r = runCatching { s.exec(cmd) }.getOrElse { "1\n${it.message}" }
            val ok = r.substringBefore('\n').trim() == "0"
            Log.i(TAG, "$label: ${if (ok) "ok" else "failed: ${r.substringAfter('\n').trim()}"}")
            label to ok
        }
    }

    fun startCapture(sampleRate: Int): ParcelFileDescriptor? = runCatching { awaitService()?.startCapture(sampleRate) }
        .onFailure { Log.w(TAG, "shell capture failed", it) }.getOrNull()

    /** Runs a shell command through the helper; returns its output, or null if unavailable. */
    fun exec(command: String): String? = runCatching { service?.exec(command)?.substringAfter('\n') }.getOrNull()

    fun stopCapture() {
        runCatching { service?.stopCapture() }
    }

    private const val TAG = "PowerMode"
}
