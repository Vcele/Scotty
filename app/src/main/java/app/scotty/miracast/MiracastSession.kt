package app.scotty.miracast

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import app.scotty.cast.CastController
import app.scotty.cast.CastTarget
import app.scotty.cast.NativeSession
import app.scotty.core.ScottyCore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Miracast: Android forms the Wi-Fi Direct group, the Rust core (already listening on 7236)
 * does the Wi-Fi Display RTSP negotiation and RTP streaming.
 */
class MiracastSession(
    ctx: Context,
    projection: MediaProjection,
    private val target: CastTarget.Miracast,
    onEnded: (String?) -> Unit,
) : NativeSession(
    ctx, projection, ctx.resources.displayMetrics.densityDpi, rawPcm = false, reconnect = false, credentialKey = null, onEnded = onEnded,
    starter = { ScottyCore.miracastStart(ScottyCore.MIRACAST_PORT) },
) {
    private var receiver: BroadcastReceiver? = null
    private var wfdViaShell = false

    override fun prepare() {
        // Strict sinks (Samsung TVs) ignore Wi-Fi Direct requests that don't advertise a Wi-Fi Display
        // source. Only privileged code may set that; power mode's shell helper can.
        val viaShell = app.scotty.shell.PowerMode.setWfdSource(true)
        wfdViaShell = viaShell == "ok"
        ScottyCore.log(TAG, "Miracast source advertisement via power mode: ${viaShell ?: "power mode off"}")
        if (!wfdViaShell) {
            P2p.tryAdvertiseWfdSource(ctx) { ok -> ScottyCore.log(TAG, "Miracast source advertisement as app: ${if (ok) "ok" else "refused"}") }
        }
        CastController.status("Connecting over Wi-Fi Direct… accept the request on your TV if asked")
        val info = connectP2p()
        ScottyCore.log(TAG, "group formed, we are ${if (info.isGroupOwner) "group owner" else "client"}, GO ${info.groupOwnerAddress}")
        CastController.status("Waiting for ${target.name} to start the session…")
    }

    @SuppressLint("MissingPermission")
    private fun connectP2p(): WifiP2pInfo {
        val (m, c) = P2p.get(ctx) ?: error("Wi-Fi Direct unavailable")
        val formed = CompletableFuture<WifiP2pInfo>()
        var lastPeerStatus = -1
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> m.requestConnectionInfo(c) { info ->
                        ScottyCore.log(TAG, "connection changed: groupFormed=${info?.groupFormed}, owner=${info?.isGroupOwner}")
                        if (info?.groupFormed == true) formed.complete(info)
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> m.requestPeers(c) { peers ->
                        val d = peers?.deviceList?.firstOrNull { it.deviceAddress == target.deviceAddress } ?: return@requestPeers
                        if (d.status != lastPeerStatus) {
                            lastPeerStatus = d.status
                            val s = when (d.status) {
                                0 -> "connected"; 1 -> "invited (waiting for the TV)"; 2 -> "failed / rejected by the TV"
                                3 -> "available"; 4 -> "unavailable"; else -> "status ${d.status}"
                            }
                            ScottyCore.log(TAG, "TV status: $s")
                        }
                    }
                }
            }
        }
        receiver = rec
        ContextCompat.registerReceiver(
            ctx, rec,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The TV must be in the phone's *current* peer list, or connect fails with a generic error.
        // Changing the Wi-Fi Display setting refreshes Wi-Fi Direct, so re-scan and wait for the TV.
        waitForPeer(m, c, 8000)
        val config = WifiP2pConfig().apply {
            deviceAddress = target.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 14
        }
        fun attempt(n: Int) {
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = ScottyCore.log(TAG, "Wi-Fi Direct invitation sent to ${target.name} (attempt $n)")
                override fun onFailure(reason: Int) {
                    val why = when (reason) { 0 -> "error"; 1 -> "Wi-Fi Direct unsupported"; 2 -> "busy"; else -> "reason $reason" }
                    ScottyCore.log(TAG, "Wi-Fi Direct connect refused locally: $why (attempt $n)")
                    if (n < 3 && (reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY)) {
                        m.cancelConnect(c, null)
                        if (reason == WifiP2pManager.BUSY) m.removeGroup(c, null)
                        Thread {
                            Thread.sleep(1000)
                            waitForPeer(m, c, 6000)
                            Handler(Looper.getMainLooper()).post { attempt(n + 1) }
                        }.start()
                    } else {
                        formed.completeExceptionally(IllegalStateException("Wi-Fi Direct connect failed ($why)"))
                    }
                }
            })
        }
        attempt(1)
        return try {
            try {
                formed.get(20, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                CastController.status(
                    if (wfdViaShell) "Still waiting for ${target.name}… check the TV for a connection prompt"
                    else "Still waiting… Samsung and some other TVs only answer devices that announce themselves " +
                        "as Miracast sources – start Shizuku (Power mode) and try again",
                )
                formed.get(70, TimeUnit.SECONDS)
            }
        } catch (e: TimeoutException) {
            throw IllegalStateException(
                if (wfdViaShell) "${target.name} didn't accept the Wi-Fi Direct connection"
                else "${target.name} didn't answer. Start Shizuku (Power mode) so Scotty can announce itself as a Miracast source, then try again",
            )
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }


    /** Starts discovery and waits until the target shows up as a peer (or the timeout passes). */
    @SuppressLint("MissingPermission")
    private fun waitForPeer(m: WifiP2pManager, c: WifiP2pManager.Channel, timeoutMs: Long) {
        m.discoverPeers(c, null)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val seen = CompletableFuture<Int?>()
            m.requestPeers(c) { peers -> seen.complete(peers?.deviceList?.firstOrNull { it.deviceAddress == target.deviceAddress }?.status) }
            val status = runCatching { seen.get(1, TimeUnit.SECONDS) }.getOrNull()
            if (status != null && status != 4) { // 4 = unavailable
                ScottyCore.log(TAG, "TV visible as peer (status $status)")
                return
            }
            Thread.sleep(500)
        }
        ScottyCore.log(TAG, "TV not seen in a fresh scan within ${timeoutMs / 1000}s – trying anyway")
    }

    override fun stop() {
        super.stop()
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        P2p.get(ctx)?.let { (m, c) -> m.removeGroup(c, null) }
        if (wfdViaShell) Thread { app.scotty.shell.PowerMode.setWfdSource(false) }.start()
    }

    companion object { private const val TAG = "Miracast" }
}
