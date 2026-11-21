package app.scotty.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import app.scotty.MainActivity
import app.scotty.R
import app.scotty.miracast.MiracastSession
import app.scotty.system.BeamTileService
import kotlin.math.roundToInt

/**
 * Foreground service that owns the MediaProjection and the active [CastSession], and plugs the
 * cast into the system: a remote-volume media session (volume panel + volume keys), an optional
 * mute of the phone's own speaker, the notification and the Quick Settings tile.
 */
class CastService : Service() {
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
    private var mutedMusicVolume: Int? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

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

        CastController.session?.stop()
        CastController.session = null
        teardownSystemHooks()
        projection?.stop()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: run { release(keepState = false); return START_NOT_STICKY }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system")
                if (projection === proj) shutdown()
            }
        }, main)

        acquireLocks()
        Prefs.setLastTarget(this, target)
        val volume = Prefs.volume(this)
        CastController.begin(target, volume)
        val session = when (target) {
            is CastTarget.Miracast -> MiracastSession(this, proj, target, ::onSessionEnded)
            is CastTarget.AirPlayAudio -> AirPlayAudioSession(this, proj, target, ::onSessionEnded)
            is CastTarget.AirPlayScreen -> AirPlayScreenSession(this, proj, resources.displayMetrics.densityDpi, target, ::onSessionEnded)
        }
        CastController.session = session
        setupSystemHooks(target, volume)
        session.start()
        refreshTile()
        return START_NOT_STICKY
    }

    // ---- system integration -------------------------------------------------------------------

    private fun setupSystemHooks(target: CastTarget, volume: Float) {
        val hasRemoteVolume = target is CastTarget.AirPlayAudio
        val ms = MediaSession(this, "Scotty").apply {
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, target.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Beaming with Scotty")
                    .build(),
            )
            setPlaybackState(
                PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).setActions(PlaybackState.ACTION_STOP).build(),
            )
            setCallback(object : MediaSession.Callback() {
                override fun onStop() = shutdown()
            })
        }
        if (hasRemoteVolume) {
            // Shows up as "<speaker>" slider in the system volume panel and takes volume keys when
            // the system routes them to us.
            val vp = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, (volume * 100).roundToInt()) {
                override fun onSetVolumeTo(v: Int) = CastController.setVolume(v / 100f)
                override fun onAdjustVolume(direction: Int) = CastController.setVolume((currentVolume + direction * 5) / 100f)
            }
            volumeProvider = vp
            ms.setPlaybackToRemote(vp)
            CastController.volumeListener = { v ->
                main.post { vp.currentVolume = (v * 100).roundToInt() }
                Prefs.setVolume(this, v)
            }
        }
        ms.isActive = true
        mediaSession = ms
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(target))

        if (hasRemoteVolume && Prefs.muteLocal(this)) muteLocalSpeaker()
        if (hasRemoteVolume) {
            // If the playing app blocks capture, give the phone its sound back instead of silence.
            CastController.captureBlockedListener = { blocked ->
                main.post {
                    if (blocked) unmuteLocalSpeaker()
                    else if (Prefs.muteLocal(this) && mutedMusicVolume == null && CastController.session != null) muteLocalSpeaker()
                }
            }
        }
    }

    /**
     * Playback capture happens before the phone's volume is applied, so the phone can stay silent
     * while the receiver gets full audio. Volume-key presses that would unmute the phone are
     * turned into receiver volume changes instead – like an iPhone with an AirPlay speaker.
     */
    private fun muteLocalSpeaker() {
        val stream = AudioManager.STREAM_MUSIC
        mutedMusicVolume = audio.getStreamVolume(stream)
        // Park at step 1 *and* muted: silent, yet both volume-up and volume-down still change the
        // index, so every key press is visible to us and can be redirected to the receiver.
        audio.setStreamVolume(stream, PARK_INDEX, 0)
        audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(EXTRA_STREAM_TYPE, -1) != stream || mutedMusicVolume == null) return
                val value = i.getIntExtra(EXTRA_STREAM_VALUE, PARK_INDEX)
                if (value == PARK_INDEX) {
                    if (!audio.isStreamMute(stream)) audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    return
                }
                val current = (CastController.state.value as? CastState.Active)?.volume ?: 0.8f
                CastController.setVolume(current + (value - PARK_INDEX).toFloat() / max)
                audio.setStreamVolume(stream, PARK_INDEX, 0)
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            }
        }
        volumeReceiver = rec
        ContextCompat.registerReceiver(this, rec, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun unmuteLocalSpeaker() {
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        volumeReceiver = null
        mutedMusicVolume?.let {
            runCatching {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            }
        }
        mutedMusicVolume = null
    }

    private fun teardownSystemHooks() {
        unmuteLocalSpeaker()
        CastController.captureBlockedListener = null
        CastController.volumeListener = null
        mediaSession?.release()
        mediaSession = null
        volumeProvider = null
    }

    private fun refreshTile() {
        runCatching { TileService.requestListeningState(this, ComponentName(this, BeamTileService::class.java)) }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Called by a session when it ends on its own (error or remote hang-up). */
    private fun onSessionEnded(error: String?) {
        main.post {
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
        teardownSystemHooks()
        runCatching { projection?.stop() }
        projection = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        if (!keepState) CastController.idle()
        stopForeground(STOP_FOREGROUND_REMOVE)
        refreshTile()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scotty:cast").apply { acquire(6 * 60 * 60 * 1000L) }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Scotty:cast").apply { acquire() }
    }

    private fun buildNotification(target: CastTarget): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Beaming", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val what = when (target) {
            is CastTarget.Miracast -> "Mirroring screen via Miracast"
            is CastTarget.AirPlayAudio -> "Playing audio on AirPlay speaker"
            is CastTarget.AirPlayScreen -> "Streaming screen via AirPlay"
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_beam)
            .setContentTitle("Beaming to ${target.name}")
            .setContentText(what)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        mediaSession?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0)) }
        return b.build()
    }

    override fun onDestroy() {
        CastController.session?.stop()
        CastController.session = null
        teardownSystemHooks()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 7236
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "app.scotty.STOP"
        // Hidden-but-stable AudioManager broadcast used by many volume apps.
        private const val PARK_INDEX = 1
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

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
