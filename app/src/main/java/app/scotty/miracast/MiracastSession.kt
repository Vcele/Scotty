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

    override fun prepare() {
        P2p.tryAdvertiseWfdSource(ctx) { ok -> Log.i(TAG, "WFD source IE advertised: $ok") }
        CastController.status("Connecting over Wi-Fi Direct… accept the request on your TV if asked")
        val info = connectP2p()
        Log.i(TAG, "group formed, GO=${info.isGroupOwner} goAddr=${info.groupOwnerAddress}")
        CastController.status("Waiting for ${target.name} to start the session…")
    }

    @SuppressLint("MissingPermission")
    private fun connectP2p(): WifiP2pInfo {
        val (m, c) = P2p.get(ctx) ?: error("Wi-Fi Direct unavailable")
        val formed = CompletableFuture<WifiP2pInfo>()
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                m.requestConnectionInfo(c) { info -> if (info?.groupFormed == true) formed.complete(info) }
            }
        }
        receiver = rec
        ContextCompat.registerReceiver(
            ctx, rec, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        m.stopPeerDiscovery(c, null)
        val config = WifiP2pConfig().apply {
            deviceAddress = target.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 14
        }
        fun attempt(retry: Boolean) {
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    if (retry && reason == WifiP2pManager.BUSY) {
                        m.cancelConnect(c, null)
                        m.removeGroup(c, null)
                        Handler(Looper.getMainLooper()).postDelayed({ attempt(false) }, 1500)
                    } else {
                        formed.completeExceptionally(IllegalStateException("Wi-Fi Direct connect failed (code $reason)"))
                    }
                }
            })
        }
        attempt(true)
        return try {
            formed.get(90, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IllegalStateException("${target.name} didn't accept the Wi-Fi Direct connection")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun stop() {
        super.stop()
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        P2p.get(ctx)?.let { (m, c) -> m.removeGroup(c, null) }
    }

    companion object { private const val TAG = "Miracast" }
}
