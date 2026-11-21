package app.scotty.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.util.ArrayDeque

data class AirPlayDevice(
    val name: String,
    val host: String,
    val raopPort: Int? = null,
    val airplayPort: Int? = null,
    val model: String? = null,
    val features: Long = 0,
    val needsPassword: Boolean = false,
    val manual: Boolean = false,
) {
    /** Features bit 0 = video (the /play URL API). */
    val supportsVideo get() = airplayPort != null && (features and 1L) != 0L
    val supportsAudio get() = raopPort != null || (airplayPort != null && prefersAirPlay2)
    val isAppleTv get() = model?.startsWith("AppleTV") == true
    /**
     * Feature bit 48 (SupportsCoreUtilsPairingAndEncryption) = full AirPlay 2 receiver. Many of
     * them (Sonos, current TVs/AVRs) accept AirPlay 1 but then play nothing, so they get
     * AirPlay 2 first. Apple devices always do.
     */
    val prefersAirPlay2 get() = (features and (1L shl 48)) != 0L || isAppleTv || model?.startsWith("AudioAccessory") == true
    val ap2Port get() = airplayPort ?: raopPort ?: 7000
    val isSpeaker get() = model?.let { it.startsWith("AudioAccessory") || it.startsWith("AirPort") } == true ||
        (airplayPort == null && raopPort != null)
}

/** Finds AirPlay receivers on the local network via mDNS (_raop._tcp + _airplay._tcp). */
class AirPlayDiscovery(private val ctx: Context) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val _devices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDevice>> = _devices

    private val byName = LinkedHashMap<String, AirPlayDevice>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val main = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private val prefs = ctx.getSharedPreferences("airplay", Context.MODE_PRIVATE)

    init {
        loadManual()
    }

    /** Adds a receiver by address (for networks where mDNS is blocked). Port 7000 serves audio + video on AirPlay 2 devices. */
    fun addManual(host: String, port: Int) {
        val set = prefs.getStringSet(MANUAL, emptySet())!!.toMutableSet()
        set += "$host:$port"
        prefs.edit().putStringSet(MANUAL, set).apply()
        loadManual()
    }

    fun removeManual(device: AirPlayDevice) {
        val set = prefs.getStringSet(MANUAL, emptySet())!!.toMutableSet()
        set.removeAll { it.substringBeforeLast(':') == device.host }
        prefs.edit().putStringSet(MANUAL, set).apply()
        synchronized(byName) {
            byName.remove(device.name)
            _devices.value = byName.values.sortedBy { it.name.lowercase() }
        }
    }

    private fun loadManual() = synchronized(byName) {
        for (entry in prefs.getStringSet(MANUAL, emptySet())!!) {
            val host = entry.substringBeforeLast(':')
            val port = entry.substringAfterLast(':').toIntOrNull() ?: continue
            byName.putIfAbsent(host, AirPlayDevice(host, host, raopPort = port, airplayPort = port, features = 1, manual = true))
        }
        _devices.value = byName.values.sortedBy { it.name.lowercase() }
    }

    fun start() {
        if (listeners.isNotEmpty()) return
        multicastLock = ctx.getSystemService(WifiManager::class.java)
            .createMulticastLock("Scotty:mdns").apply { setReferenceCounted(false); acquire() }
        for (type in listOf(RAOP, AIRPLAY)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery $serviceType failed $errorCode") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) = main.post { enqueue(info) }.let { }
                override fun onServiceLost(info: NsdServiceInfo) = main.post { lost(info) }.let { }
            }
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
            listeners += l
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        multicastLock?.release()
    }

    fun refresh() {
        stop()
        synchronized(byName) { byName.clear() }
        loadManual()
        start()
    }

    private fun displayName(info: NsdServiceInfo): String =
        if (info.serviceType.contains("raop")) info.serviceName.substringAfter('@') else info.serviceName

    private fun enqueue(info: NsdServiceInfo) {
        resolveQueue += info
        pump()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val next = resolveQueue.pollFirst() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) resolveQueue.addLast(info)
                main.postDelayed({ pump() }, 100)
            }.let { }

            override fun onServiceResolved(info: NsdServiceInfo) = main.post {
                resolving = false
                resolved(info)
                pump()
            }.let { }
        })
    }

    private fun resolved(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        val addrs = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val addr = addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull() ?: return
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        val name = displayName(info)
        Log.i(TAG, "found \"$name\" ${info.serviceType} ${addr.hostAddress}:${info.port} txt=$txt")
        val isRaop = info.serviceType.contains("raop")
        synchronized(byName) {
            val old = byName[name] ?: AirPlayDevice(name, addr.hostAddress!!)
            byName[name] = if (isRaop) {
                old.copy(
                    host = addr.hostAddress!!, raopPort = info.port,
                    model = old.model ?: txt["am"],
                    needsPassword = old.needsPassword || txt["pw"] == "true",
                )
            } else {
                old.copy(
                    host = addr.hostAddress!!, airplayPort = info.port,
                    model = txt["model"] ?: old.model,
                    features = parseFeatures(txt["features"]),
                    needsPassword = old.needsPassword || txt["pw"] == "1" || txt["pw"] == "true",
                )
            }
            _devices.value = byName.values.sortedBy { it.name.lowercase() }
        }
    }

    private fun lost(info: NsdServiceInfo) {
        val name = displayName(info)
        val isRaop = info.serviceType.contains("raop")
        synchronized(byName) {
            val old = byName[name] ?: return
            val upd = if (isRaop) old.copy(raopPort = null) else old.copy(airplayPort = null)
            if (upd.raopPort == null && upd.airplayPort == null) byName.remove(name) else byName[name] = upd
            _devices.update { byName.values.sortedBy { it.name.lowercase() } }
        }
    }

    private fun parseFeatures(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val parts = s.split(',').map { it.trim().removePrefix("0x").toLongOrNull(16) ?: 0L }
        return parts[0] or ((parts.getOrNull(1) ?: 0L) shl 32)
    }

    companion object {
        private const val TAG = "AirPlayDiscovery"
        private const val RAOP = "_raop._tcp"
        private const val AIRPLAY = "_airplay._tcp"
        private const val MANUAL = "manual"
    }
}
