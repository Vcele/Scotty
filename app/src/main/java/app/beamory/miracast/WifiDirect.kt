package app.beamory.miracast

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared Wi-Fi Direct (P2P) handle. */
object P2p {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    fun get(ctx: Context): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        if (manager == null) {
            val m = ctx.applicationContext.getSystemService(WifiP2pManager::class.java) ?: return null
            manager = m
            channel = m.initialize(ctx.applicationContext, Looper.getMainLooper()) { channel = null; manager = null }
        }
        val m = manager ?: return null
        val c = channel ?: return null
        return m to c
    }

    /**
     * Tries to advertise ourselves as a Wi-Fi Display *source* in the P2P information element.
     * This is a system API (needs CONFIGURE_WIFI_DISPLAY) so it normally fails for regular apps –
     * most sinks still accept the connection, some insist on it.
     */
    fun tryAdvertiseWfdSource(ctx: Context, onResult: (Boolean) -> Unit) {
        val (m, c) = get(ctx) ?: return onResult(false)
        try {
            val info = WifiP2pWfdInfo().apply {
                setEnabled(true)
                setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_WFD_SOURCE)
                setSessionAvailable(true)
                setControlPort(WfdSource.RTSP_PORT)
                setMaxThroughput(50)
            }
            val method = WifiP2pManager::class.java.getMethod(
                "setWfdInfo", WifiP2pManager.Channel::class.java, WifiP2pWfdInfo::class.java,
                WifiP2pManager.ActionListener::class.java,
            )
            method.invoke(m, c, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onResult(true)
                override fun onFailure(reason: Int) = onResult(false)
            })
        } catch (e: Throwable) {
            Log.i("P2p", "WFD IE not settable: ${e.cause?.message ?: e.message}")
            onResult(false)
        }
    }
}

data class WfdDevice(
    val name: String,
    val address: String,
    val isMiracastSink: Boolean,
    val isDisplay: Boolean,
    val status: Int,
) {
    val statusText get() = when (status) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> null
    }
}

/** Keeps a live list of nearby Wi-Fi Direct peers, Miracast sinks first. */
class WfdDiscovery(private val ctx: Context) {
    private val _devices = MutableStateFlow<List<WfdDevice>>(emptyList())
    val devices: StateFlow<List<WfdDevice>> = _devices
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val (m, c) = P2p.get(ctx) ?: return
                    m.requestPeers(c) { list -> _devices.value = list.deviceList.map(::toDevice).sortedWith(ORDER) }
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0)
                    _scanning.value = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                }
            }
        }
    }

    private val rescan = object : Runnable {
        override fun run() {
            discover()
            handler.postDelayed(this, 25_000)
        }
    }

    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        handler.removeCallbacks(rescan)
        handler.post(rescan)
    }

    fun stop() {
        handler.removeCallbacks(rescan)
        if (registered) {
            runCatching { ctx.unregisterReceiver(receiver) }
            registered = false
        }
        P2p.get(ctx)?.let { (m, c) -> m.stopPeerDiscovery(c, null) }
        _scanning.value = false
    }

    @SuppressLint("MissingPermission")
    private fun discover() {
        val (m, c) = P2p.get(ctx) ?: run { _error.value = "Wi-Fi Direct is not available on this device"; return }
        try {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { _error.value = null }
                override fun onFailure(reason: Int) {
                    _error.value = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                        WifiP2pManager.BUSY -> null // already scanning / connecting
                        else -> "Turn on Wi-Fi to find Miracast displays"
                    }
                }
            })
        } catch (e: SecurityException) {
            _error.value = "Allow “Nearby devices” to find Miracast displays"
        }
    }

    private fun toDevice(d: WifiP2pDevice): WfdDevice {
        val wfd = d.wfdInfo
        val sink = wfd != null && wfd.isEnabled && wfd.deviceType != WifiP2pWfdInfo.DEVICE_TYPE_WFD_SOURCE
        // WPS primary device type category 7 = Displays
        val display = d.primaryDeviceType?.startsWith("7-") == true
        return WfdDevice(d.deviceName.ifBlank { d.deviceAddress }, d.deviceAddress, sink, display, d.status)
    }

    companion object {
        private val ORDER = compareByDescending<WfdDevice> { it.isMiracastSink }
            .thenByDescending { it.isDisplay }
            .thenBy { it.name.lowercase() }
    }
}
