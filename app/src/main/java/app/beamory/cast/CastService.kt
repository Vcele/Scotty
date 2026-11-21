package app.beamory.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import app.beamory.MainActivity
import app.beamory.R
import app.beamory.airplay.AirPlayScreenSession
import app.beamory.airplay.RaopSession
import app.beamory.miracast.MiracastSession

/**
 * Foreground service that owns the MediaProjection and the active [CastSession].
 */
class CastService : Service() {
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val target = CastController.pendingTarget
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (target == null || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        CastController.pendingTarget = null
        startForeground(NOTIFICATION_ID, buildNotification(target), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        // Stop a previous session first.
        CastController.session?.stop()
        CastController.session = null
        projection?.stop()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: run { release(keepState = false); return START_NOT_STICKY }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system")
                if (projection === proj) shutdown()
            }
        }, Handler(Looper.getMainLooper()))

        acquireLocks()
        CastController.begin(target)
        val session = when (target) {
            is CastTarget.Miracast -> MiracastSession(this, proj, target, ::onSessionEnded)
            is CastTarget.AirPlayAudio -> RaopSession(proj, target, ::onSessionEnded)
            is CastTarget.AirPlayScreen -> AirPlayScreenSession(this, proj, target, ::onSessionEnded)
        }
        CastController.session = session
        session.start()
        return START_NOT_STICKY
    }

    /** Called by a session when it ends on its own (error or remote hang-up). */
    private fun onSessionEnded(error: String?) {
        Handler(Looper.getMainLooper()).post {
            if (error != null) CastController.error(error)
            CastController.session = null
            release(keepState = error != null)
        }
    }

    private fun shutdown() {
        CastController.session?.stop()
        CastController.session = null
        release(keepState = false)
    }

    private fun release(keepState: Boolean) {
        runCatching { projection?.stop() }
        projection = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        if (!keepState) CastController.idle()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Beamory:cast").apply { acquire(6 * 60 * 60 * 1000L) }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Beamory:cast").apply { acquire() }
    }

    private fun buildNotification(target: CastTarget): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val what = when (target) {
            is CastTarget.Miracast -> "Mirroring screen via Miracast"
            is CastTarget.AirPlayAudio -> "Streaming audio via AirPlay"
            is CastTarget.AirPlayScreen -> "Streaming screen via AirPlay"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_beam)
            .setContentTitle("Beaming to ${target.name}")
            .setContentText(what)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        CastController.session?.stop()
        CastController.session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 7236
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "app.beamory.STOP"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, CastService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(ctx: Context) {
            if (CastController.session == null) {
                CastController.idle()
                return
            }
            ctx.startService(Intent(ctx, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}
