package local.tickets.companion

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One bounded UTF-8 JSON request per connection. No address other than device loopback. */
class LocalBridge {
    @Volatile private var running = true
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    fun start() {
        Thread({
            try {
                server = ServerSocket(18765, 2, InetAddress.getByName("127.0.0.1"))
                Session.bridgeState = "listening"
                while (running) {
                    val socket = server?.accept() ?: break
                    client = socket
                    try {
                        socket.use {
                            it.soTimeout = 1500
                            val response = try {
                                val line = readBoundedLine(it)
                                val pairing = PairingProtocol.parse(line)
                                if (pairing != null) onMain(null, pairing)
                                else onMain(Protocol.parse(line, Session.token).action)
                            } catch (_: Exception) {
                                Session.envelope().put("ok", false).put("error", "invalid_or_unauthorized_request")
                            }
                            it.getOutputStream().write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
                        }
                    } catch (_: Exception) {
                        // A disconnect/malformed client must not stop the listener or log its input.
                    } finally { client = null }
                }
            } catch (_: Exception) {
                if (running) Session.bridgeState = "unavailable"
            } finally {
                try { client?.close() } catch (_: Exception) { }
                try { server?.close() } catch (_: Exception) { }
            }
        }, "safe-local-bridge").apply { isDaemon = true; start() }
    }
    private fun readBoundedLine(socket: Socket): String {
        val buffer = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (buffer.size() <= Protocol.MAX_BYTES) {
            require(System.nanoTime() < deadline) { "timeout" }
            val byte = socket.getInputStream().read()
            require(byte != -1) { "incomplete" }
            if (byte == 10) return buffer.toString(Charsets.UTF_8.name())
            require(byte != 0) { "invalid" }
            buffer.write(byte)
        }
        throw IllegalArgumentException("too_large")
    }
    private fun onMain(action: String?, pairing: PairAttempt? = null): JSONObject {
        val latch = CountDownLatch(1)
        val pending = AtomicBoolean(true)
        val waitingError = if (pairing != null) "pairing_failed" else "busy"
        var result = Session.envelope().put("ok", false).put("error", waitingError)
        Session.main.post {
            if (!running || !pending.compareAndSet(true, false)) { latch.countDown(); return@post }
            try {
                result = if (pairing != null) Session.pair(pairing) else when (action) {
                    "capabilities" -> Session.capabilities()
                    "status" -> Session.status()
                    "inspect" -> Session.inspect()
                    "native_audit" -> Session.nativeAudit()
                    "stop" -> { Session.stop(); Session.status() }
                    else -> Session.envelope().put("ok", false).put("error", "unsupported")
                }
            } catch (_: Exception) {
                result = Session.envelope().put("ok", false).put("error", if (pairing != null) "pairing_failed" else "inspection_unavailable")
            } finally { latch.countDown() }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            pending.set(false)
            return Session.envelope().put("ok", false).put("error", waitingError)
        }
        return result
    }
    fun close() {
        running = false
        Session.bridgeState = "stopped"
        try { client?.close() } catch (_: Exception) { }
        try { server?.close() } catch (_: Exception) { }
    }
}
