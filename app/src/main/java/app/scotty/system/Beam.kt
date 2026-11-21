package app.scotty.system

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/** Shared helpers for starting a cast from any entry point (app, tile, shortcut). */
object Beam {
    fun projectionIntent(ctx: Context): Intent {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
    }
}

/**
 * Power mode shortcut: AirPlay audio captured by the Shizuku shell helper needs neither the
 * microphone permission nor the screen-capture prompt. Returns false if power mode isn't ready.
 */
fun Beam.tryDirect(ctx: Context, target: app.scotty.cast.CastTarget): Boolean {
    if (target !is app.scotty.cast.CastTarget.AirPlayAudio || !app.scotty.shell.PowerMode.canCaptureDirect) return false
    app.scotty.cast.CastController.pendingTarget = target
    app.scotty.cast.CastService.startDirect(ctx)
    return true
}
