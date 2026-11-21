package app.scotty.system

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.scotty.R
import app.scotty.cast.CastController
import app.scotty.cast.CastService
import app.scotty.cast.CastState
import app.scotty.cast.Prefs

/**
 * Quick Settings tile: tap to beam audio to the last AirPlay speaker (or pick one), tap again to
 * stop. Long-press opens the speaker picker.
 */
class BeamTileService : TileService() {
    override fun onStartListening() {
        app.scotty.shell.PowerMode.init(this)
        update()
    }

    override fun onClick() {
        if (CastController.isActive) {
            CastService.stop(this)
            update()
            return
        }
        val intent = Intent(this, BeamActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(BeamActivity.EXTRA_DIRECT, Prefs.lastTarget(this) != null)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val state = CastController.state.value
        val active = CastController.isActive && state is CastState.Active
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Scotty"
        tile.subtitle = when {
            active -> (state as CastState.Active).target.name
            else -> Prefs.lastTarget(this)?.name ?: "Choose speaker"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_beam)
        tile.updateTile()
    }
}
