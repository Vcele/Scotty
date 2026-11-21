package app.beamory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.beamory.airplay.AirPlayDiscovery
import app.beamory.miracast.WfdDiscovery

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val miracast = WfdDiscovery(app)
    val airplay = AirPlayDiscovery(app)

    fun startDiscovery() {
        runCatching { miracast.start() }
        runCatching { airplay.start() }
    }

    fun stopDiscovery() {
        miracast.stop()
        airplay.stop()
    }

    fun refresh() {
        miracast.start()
        airplay.refresh()
    }

    override fun onCleared() = stopDiscovery()
}
