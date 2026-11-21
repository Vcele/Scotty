package app.beamory.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Where to cast to. */
sealed interface CastTarget {
    val name: String

    data class Miracast(override val name: String, val deviceAddress: String) : CastTarget

    data class AirPlayAudio(override val name: String, val host: String, val port: Int) : CastTarget

    data class AirPlayScreen(override val name: String, val host: String, val port: Int) : CastTarget
}

interface CastSession {
    fun start()
    fun stop()
    fun setVolume(volume: Float) {}
}

sealed interface CastState {
    data object Idle : CastState
    data class Active(
        val target: CastTarget,
        val status: String,
        val live: Boolean = false,
        val error: Boolean = false,
        val volume: Float = 0.8f,
    ) : CastState
}

/** Process-wide cast state shared between the UI and [CastService]. */
object CastController {
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state

    @Volatile var pendingTarget: CastTarget? = null
    @Volatile internal var session: CastSession? = null

    fun begin(target: CastTarget) {
        _state.value = CastState.Active(target, "Starting…")
    }

    fun status(text: String, live: Boolean = false) = _state.update {
        if (it is CastState.Active) it.copy(status = text, live = live, error = false) else it
    }

    fun error(text: String) = _state.update {
        if (it is CastState.Active) it.copy(status = text, live = false, error = true) else it
    }

    fun setVolume(v: Float) {
        _state.update { if (it is CastState.Active) it.copy(volume = v) else it }
        session?.setVolume(v)
    }

    fun clearIfError() {
        val s = _state.value
        if (s is CastState.Active && s.error && session == null) _state.value = CastState.Idle
    }

    internal fun idle() {
        _state.value = CastState.Idle
    }
}
