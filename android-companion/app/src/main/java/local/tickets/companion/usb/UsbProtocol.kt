package local.tickets.companion.usb

import local.tickets.companion.execution.LocalPlan
import local.tickets.companion.policy.TicketTarget
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Fixed binary request schema: private labels never appear in shell arguments or temporary files.
 * Status replies are separately filtered JSON. No opcode exists for submission/payment or raw input.
 */
internal object UsbProtocol {
    const val VERSION = 1
    const val MAGIC = 0x54555342 // TUSB
    const val MAX_FRAME_BYTES = 32768
    const val LEASE_MS = 3000L
    const val CONNECT_MS = 10000L
    const val HEARTBEAT = 1
    const val CANCEL = 2

    enum class Operation(val code: Int) { PROBE(0), INSPECT(1), OPEN_PICKER(2), SELECT_SESSION(3), PREPARE(4), RUN(5) }

    class Request(val operation: Operation, val eventTitle: String = "", val session: String = "", val plan: LocalPlan? = null) {
        override fun toString() = "UsbRequest(${operation.name}, redacted)"
    }
    data class Control(val kind: Int, val sequence: Long)
    class Invalid(val reason: String) : IllegalArgumentException(reason)

    fun readFrame(input: InputStream): ByteArray {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        if (size !in 1..MAX_FRAME_BYTES) throw Invalid("frame_size_invalid")
        return ByteArray(size).also(stream::readFully)
    }

    fun writeFrame(output: OutputStream, frame: ByteArray) {
        if (frame.size !in 1..MAX_FRAME_BYTES) throw Invalid("frame_size_invalid")
        DataOutputStream(output).apply { writeInt(frame.size); write(frame); flush() }
    }

    private fun string(input: DataInputStream, maxCharacters: Int): String {
        val size = input.readInt()
        if (size !in 1..(maxCharacters * 4) || size > input.available()) throw Invalid("string_size_invalid")
        val bytes = ByteArray(size).also(input::readFully)
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw Invalid("utf8_invalid") }
        if (text.length > maxCharacters || text.isBlank() || text.any(Char::isISOControl)) throw Invalid("label_invalid")
        return text
    }

    fun decodeRequest(frame: ByteArray, expectedCapability: String, nowWallMs: Long): Request {
        if (frame.size !in 1..MAX_FRAME_BYTES || !Regex("[0-9a-f]{64}").matches(expectedCapability)) throw Invalid("request_invalid")
        try {
            val input = DataInputStream(ByteArrayInputStream(frame))
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw Invalid("protocol_version_invalid")
            val supplied = string(input, 64)
            if (!MessageDigest.isEqual(supplied.toByteArray(StandardCharsets.UTF_8), expectedCapability.toByteArray(StandardCharsets.UTF_8)))
                throw Invalid("capability_invalid")
            val code = input.readUnsignedByte()
            val operation = Operation.entries.singleOrNull { it.code == code } ?: throw Invalid("operation_invalid")
            val request = when (operation) {
                Operation.PROBE, Operation.INSPECT -> Request(operation)
                Operation.OPEN_PICKER -> Request(operation, eventTitle = string(input, 300))
                Operation.SELECT_SESSION -> Request(operation, eventTitle = string(input, 300), session = string(input, 300))
                Operation.PREPARE, Operation.RUN -> {
                    val event = string(input, 300)
                    val session = string(input, 300)
                    val tier = string(input, 300)
                    val unitPrice = input.readLong()
                    val quantity = input.readInt()
                    val maximum = input.readLong()
                    val requestedStart = input.readLong()
                    val timeoutSeconds = input.readInt()
                    val buyerCount = input.readInt()
                    if (unitPrice !in 1..2_000_000_000L || quantity !in 1..20 || maximum != unitPrice * quantity ||
                        timeoutSeconds !in 10..600 || buyerCount !in 0..20 ||
                        (buyerCount != 0 && buyerCount != quantity) || requestedStart < 0 ||
                        (operation == Operation.PREPARE && requestedStart != 0L)) throw Invalid("plan_invalid")
                    val buyers = (0 until buyerCount).map { string(input, 300) }
                    val start = if (requestedStart == 0L) nowWallMs else requestedStart
                    if (start < nowWallMs || start - nowWallMs > 24 * 3600_000L) throw Invalid("start_time_invalid")
                    val plan = LocalPlan(event, listOf(TicketTarget(session, tier, unitPrice)), quantity, maximum,
                        start, start + timeoutSeconds * 1000L, priorityWindowMs = 0, waitForStock = false,
                        autoSubmit = false, buyerLabels = buyers)
                    try { plan.validate(nowWallMs) } catch (_: IllegalArgumentException) { throw Invalid("plan_invalid") }
                    Request(operation, event, session, plan)
                }
            }
            if (input.available() != 0) throw Invalid("unexpected_request_fields")
            return request
        } catch (error: Invalid) { throw error }
        catch (_: Exception) { throw Invalid("request_truncated_or_invalid") }
    }

    fun decodeControl(frame: ByteArray, previousSequence: Long): Control {
        if (frame.size != 9) throw Invalid("control_size_invalid")
        val input = DataInputStream(ByteArrayInputStream(frame))
        val kind = input.readUnsignedByte()
        val sequence = input.readLong()
        if (kind !in setOf(HEARTBEAT, CANCEL) || sequence <= previousSequence || sequence <= 0) throw Invalid("control_invalid")
        return Control(kind, sequence)
    }
}

/** A host disconnect or missing heartbeat revokes the session independently of a blocked UI read. */
internal class UsbLease(private val beganAt: Long) {
    @Volatile private var lastHeartbeatAt = beganAt
    @Volatile private var cancelled = false
    @Volatile var reason = "none"
        private set
    @Volatile private var connected = false

    @Synchronized fun connect(now: Long): Boolean {
        if (cancelled || connected || now < beganAt || now - beganAt >= UsbProtocol.CONNECT_MS) return false
        connected = true
        lastHeartbeatAt = now
        return true
    }
    @Synchronized fun heartbeat(now: Long): Boolean {
        if (!isLive(now) || !connected || now < lastHeartbeatAt) return false
        lastHeartbeatAt = now
        return true
    }
    @Synchronized fun cancel(why: String) {
        if (!cancelled) { cancelled = true; reason = why }
    }
    @Synchronized fun isLive(now: Long): Boolean {
        if (cancelled) return false
        if (now < beganAt || now < lastHeartbeatAt) { cancel("clock_regressed"); return false }
        if (now - lastHeartbeatAt >= if (connected) UsbProtocol.LEASE_MS else UsbProtocol.CONNECT_MS) {
            cancel(if (connected) "usb_lease_expired" else "usb_connection_timeout")
            return false
        }
        return true
    }
}
