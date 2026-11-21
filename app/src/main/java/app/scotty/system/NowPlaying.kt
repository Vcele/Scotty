package app.scotty.system

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.io.ByteArrayOutputStream

/**
 * Empty listener: enabling it ("Notification access") is Android's only way to let an app read
 * other apps' media sessions – which is what we need to show title, artist and cover on the
 * AirPlay speaker like an iPhone does. Scotty never reads notification contents.
 */
class NowPlayingListener : NotificationListenerService()

data class Track(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val artworkJpeg: ByteArray?,
    val playing: Boolean,
) {
    val key get() = "$packageName|$title|$artist|$album"
}

/** Follows whichever app is currently playing and reports track / position changes. */
class NowPlaying(private val ctx: Context, private val onChange: (Track) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, NowPlayingListener::class.java)
    private var controller: MediaController? = null
    private var lastKey: String? = null
    private var lastArtKey: String? = null
    private var lastArt: ByteArray? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit(force = false)
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit(force = true)
        override fun onSessionDestroyed() = pick(null)
    }

    fun start(): Boolean {
        if (!isEnabled(ctx)) return false
        return try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            pick(null)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notification access missing", e)
            false
        }
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** The package currently playing (for diagnostics), if known. */
    val currentPackage: String? get() = controller?.packageName

    private fun pick(list: List<MediaController>?) {
        val sessions = list ?: runCatching { msm.getActiveSessions(component) }.getOrNull() ?: return
        val best = sessions
            .filter { it.packageName != ctx.packageName }
            .maxByOrNull { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        lastKey = null
        emit(force = true)
    }

    private fun emit(force: Boolean) {
        val c = controller ?: return
        val md = c.metadata ?: return
        val state = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val key = "${c.packageName}|$title|$artist|$album"
        if (!force && key == lastKey) return
        lastKey = key
        if (key != lastArtKey) {
            val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            lastArt = bmp?.let(::jpeg)
            lastArtKey = key
        }
        val position = state?.let {
            if (it.state == PlaybackState.STATE_PLAYING && it.lastPositionUpdateTime > 0) {
                it.position + ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
            } else it.position
        } ?: 0L
        onChange(
            Track(
                c.packageName, title, artist, album,
                md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0), position.coerceAtLeast(0),
                lastArt, state?.state == PlaybackState.STATE_PLAYING,
            ),
        )
    }

    private fun jpeg(b: Bitmap): ByteArray? = runCatching {
        val max = 500
        val scaled = if (b.width > max || b.height > max) {
            val s = max.toFloat() / maxOf(b.width, b.height)
            Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
        } else b
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }.getOrNull()

    companion object {
        private const val TAG = "NowPlaying"
        fun isEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}
