package app.scotty.core

/** JNI bridge to the Rust protocol core (`core/` → libscotty_core.so). */
object ScottyCore {
    init {
        System.loadLibrary("scotty_core")
        init()
    }

    private external fun init()

    /** Miracast source listening for the sink's RTSP connection. Returns a handle or < 0. */
    external fun miracastStart(port: Int): Long

    /** AirPlay audio (RAOP). Push 16-bit LE stereo 44.1 kHz PCM. */
    external fun airplayAudioStart(host: String, port: Int, volume: Float): Long

    /** AirPlay screen + audio via HLS + /play. Push H.264 + ADTS AAC. */
    external fun airplayScreenStart(host: String, port: Int): Long

    external fun pushVideo(handle: Long, data: ByteArray, ptsUs: Long, keyFrame: Boolean)
    external fun pushAudio(handle: Long, data: ByteArray, ptsUs: Long)
    external fun setVolume(handle: Long, volume: Float)

    /** Next event as tab-separated text (`status`, `live`, `mode`, `idr`, `ended`), or null on timeout. */
    external fun pollEvent(handle: Long, timeoutMs: Int): String?

    /** Stops the session and frees the handle. */
    external fun stop(handle: Long)

    const val MIRACAST_PORT = 7236
}
