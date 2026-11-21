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
