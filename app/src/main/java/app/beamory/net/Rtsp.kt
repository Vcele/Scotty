package app.beamory.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A parsed RTSP (or HTTP – same wire format) request or response. */
class RtspMessage(
    val startLine: String,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    val isResponse get() = startLine.startsWith("RTSP/") || startLine.startsWith("HTTP/")
    val status get() = startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    val method get() = startLine.substringBefore(' ')
    val cseq get() = header("CSeq")?.trim()?.toIntOrNull() ?: -1
    val bodyText get() = String(body, Charsets.UTF_8)
    fun header(name: String) = headers[name.lowercase()]

    /** Parses a `text/parameters` body into key → value. */
    fun parameters(): Map<String, String> = bodyText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .associate { line ->
            val i = line.indexOf(':')
            if (i < 0) line to "" else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }

    override fun toString() = startLine

    companion object {
        fun read(input: InputStream): RtspMessage? {
            var start: String
            do {
                start = readLine(input) ?: return null
            } while (start.isEmpty())
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(len)
            if (len > 0) DataInputStream(input).readFully(body)
            return RtspMessage(start, headers, body)
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }
    }
}

/**
 * Bidirectional RTSP connection. Outgoing requests are matched to responses by CSeq;
 * incoming requests (Miracast sinks send those) are passed to [onRequest].
 */
class RtspConnection(
    private val socket: Socket,
    private val userAgent: String? = null,
    private val onRequest: ((RtspMessage, RtspConnection) -> Unit)? = null,
) {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<RtspMessage>>()
    private var cseq = 0
    @Volatile var closed = false
        private set
    var onClosed: (() -> Unit)? = null

    val localAddress: String get() = socket.localAddress.hostAddress ?: "0.0.0.0"
    val remoteAddress get() = socket.inetAddress!!

    fun start(): RtspConnection {
        thread(name = "rtsp-reader", isDaemon = true) {
            try {
                while (true) {
                    val msg = RtspMessage.read(input) ?: break
                    Log.d(TAG, "<< $msg")
                    if (msg.isResponse) pending.remove(msg.cseq)?.complete(msg)
                    else onRequest?.invoke(msg, this) ?: respond(msg, 501)
                }
            } catch (e: IOException) {
                if (!closed) Log.w(TAG, "reader: ${e.message}")
            } finally {
                closed = true
                pending.values.forEach { it.completeExceptionally(IOException("connection closed")) }
                pending.clear()
                onClosed?.invoke()
            }
        }
        return this
    }

    fun request(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String = "text/parameters",
        timeoutMs: Long = 10_000,
    ): RtspMessage {
        val future = CompletableFuture<RtspMessage>()
        val id: Int
        synchronized(this) { id = ++cseq }
        pending[id] = future
        val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: $id\r\n")
        userAgent?.let { sb.append("User-Agent: $it\r\n") }
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        val bytes = body?.toByteArray(Charsets.UTF_8)
        if (bytes != null) sb.append("Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n")
        sb.append("\r\n")
        Log.d(TAG, ">> $method $uri")
        write(sb.toString().toByteArray(Charsets.ISO_8859_1), bytes)
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun respond(req: RtspMessage, status: Int, headers: Map<String, String> = emptyMap(), body: String? = null) {
        val sb = StringBuilder("RTSP/1.0 $status ${reason(status)}\r\nCSeq: ${req.cseq}\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        val bytes = body?.toByteArray(Charsets.UTF_8)
        if (bytes != null) sb.append("Content-Type: text/parameters\r\nContent-Length: ${bytes.size}\r\n")
        sb.append("\r\n")
        write(sb.toString().toByteArray(Charsets.ISO_8859_1), bytes)
    }

    private fun write(head: ByteArray, body: ByteArray?) {
        synchronized(output) {
            output.write(head)
            if (body != null) output.write(body)
            output.flush()
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "Rtsp"
        fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            454 -> "Session Not Found"
            501 -> "Not Implemented"
            else -> "Status"
        }
    }
}
