package app.scotty.cast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Where to cast to. */
sealed interface CastTarget {
    val name: String

    data class Miracast(override val name: String, val deviceAddress: String) : CastTarget

    /** [protocol]: 0 = auto (AirPlay 1, upgrade to 2 if needed), 2 = AirPlay 2 straight away. */
    /** [port]: AirPlay 1 (RAOP) port, [ap2Port]: AirPlay 2 port (_airplay._tcp). */
    data class AirPlayAudio(
        override val name: String,
        val host: String,
        val port: Int,
        val protocol: Int = 0,
        val ap2Port: Int = port,
    ) : CastTarget

    data class AirPlayScreen(override val name: String, val host: String, val port: Int) : CastTarget

    fun encode(): String = when (this) {
        is Miracast -> listOf("miracast", name, deviceAddress)
        is AirPlayAudio -> listOf("audio", name, host, port.toString(), protocol.toString(), ap2Port.toString())
        is AirPlayScreen -> listOf("screen", name, host, port.toString())
    }.joinToString("\u001F")

    companion object {
        fun decode(s: String?): CastTarget? {
            val p = s?.split('\u001F') ?: return null
            return runCatching {
                when (p[0]) {
                    "miracast" -> Miracast(p[1], p[2])
                    "audio" -> AirPlayAudio(p[1], p[2], p[3].toInt(), p.getOrNull(4)?.toInt() ?: 0, p.getOrNull(5)?.toInt() ?: p[3].toInt())
                    "screen" -> AirPlayScreen(p[1], p[2], p[3].toInt())
                    else -> null
                }
            }.getOrNull()
        }
    }
}

interface CastSession {
    fun start()
    fun stop()
    fun setVolume(volume: Float) {}
    fun submitPin(pin: String) {}
}

sealed interface CastState {
    data object Idle : CastState
    data class Active(
        val target: CastTarget,
        val status: String,
        val live: Boolean = false,
        val error: Boolean = false,
        val volume: Float = 0.8f,
        /** Receiver is showing a PIN that the user must type in. */
        val pinRequested: Boolean = false,
        /** Non-fatal problem, e.g. the playing app blocks audio capture. */
        val warning: String? = null,
    ) : CastState
}

/** Small persistent settings + pairing store. */
object Prefs {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("scotty", Context.MODE_PRIVATE)

    fun lastTarget(ctx: Context): CastTarget? = CastTarget.decode(p(ctx).getString("last_target", null))
    fun setLastTarget(ctx: Context, t: CastTarget) = p(ctx).edit().putString("last_target", t.encode()).apply()

    fun volume(ctx: Context) = p(ctx).getFloat("volume", 0.8f)
    fun setVolume(ctx: Context, v: Float) = p(ctx).edit().putFloat("volume", v).apply()

    /** Silence the phone's own speaker while beaming audio (capture is unaffected). */
    fun muteLocal(ctx: Context) = p(ctx).getBoolean("mute_local", true)
    fun setMuteLocal(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("mute_local", v).apply()

    /** Power-mode grants (Shizuku) have been applied; they persist across reboots. */
    fun grantsDone(ctx: Context) = p(ctx).getBoolean("grants_done", false)
    fun setGrantsDone(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("grants_done", v).apply()

    /** AirPlay 2 long-term pairing per receiver name. */
    fun credentials(ctx: Context, device: String): String? = p(ctx).getString("creds:$device", null)
    fun setCredentials(ctx: Context, device: String, creds: String) = p(ctx).edit().putString("creds:$device", creds).apply()
    fun forgetCredentials(ctx: Context, device: String) = p(ctx).edit().remove("creds:$device").apply()
}

/** Process-wide cast state shared between the UI, the tile and [CastService]. */
object CastController {
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state

    @Volatile var pendingTarget: CastTarget? = null
    @Volatile internal var session: CastSession? = null
    /** Notified when the volume changes from any source (UI slider, volume keys). */
    @Volatile internal var volumeListener: ((Float) -> Unit)? = null
    /** Told when captured audio turns silent although music plays (true) or recovers (false). */
    @Volatile internal var captureBlockedListener: ((Boolean) -> Unit)? = null
    /** Told once audio capture starts: true = shell-level output capture (power mode). */
    @Volatile internal var routingListener: ((Boolean) -> Unit)? = null

    val isActive get() = _state.value is CastState.Active && session != null

    fun begin(target: CastTarget, volume: Float) {
        _state.value = CastState.Active(target, "Starting…", volume = volume)
    }

    fun status(text: String, live: Boolean = false) = _state.update {
        if (it is CastState.Active) it.copy(status = text, live = live, error = false) else it
    }

    fun error(text: String) = _state.update {
        if (it is CastState.Active) it.copy(status = text, live = false, error = true, pinRequested = false) else it
    }

    fun warn(text: String?) = _state.update { if (it is CastState.Active) it.copy(warning = text) else it }

    fun pinRequested() = _state.update { if (it is CastState.Active) it.copy(pinRequested = true) else it }

    fun submitPin(pin: String) {
        _state.update { if (it is CastState.Active) it.copy(pinRequested = false, status = "Pairing…") else it }
        session?.submitPin(pin)
    }

    fun setVolume(v: Float) {
        val c = v.coerceIn(0f, 1f)
        _state.update { if (it is CastState.Active) it.copy(volume = c) else it }
        session?.setVolume(c)
        volumeListener?.invoke(c)
    }

    internal fun idle() {
        _state.value = CastState.Idle
    }
}
