package local.tickets.companion.usb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class UsbProtocolTest {
    private val capability = "a".repeat(64)
    private val wall = 1_000_000L
    private fun request(operation: Int = 5, cap: String = capability, version: Int = 1,
                        quantity: Int = 1, price: Long = 88000, budget: Long = 88000,
                        start: Long = 0, timeout: Int = 120, buyers: List<String> = emptyList(), trailing: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun text(value: String) { val utf8 = value.toByteArray(Charsets.UTF_8); out.writeInt(utf8.size); out.write(utf8) }
        out.writeInt(UsbProtocol.MAGIC); out.writeInt(version); text(cap); out.writeByte(operation)
        if (operation in 2..5) text("测试演出")
        if (operation in 3..5) text("2026-11-07 19:00")
        if (operation in 4..5) {
            text("内场880元"); out.writeLong(price); out.writeInt(quantity); out.writeLong(budget)
            out.writeLong(start); out.writeInt(timeout); out.writeInt(buyers.size); buyers.forEach(::text)
        }
        if (trailing) out.writeByte(1)
        return bytes.toByteArray()
    }
    private fun rejects(bytes: ByteArray, reason: String? = null) {
        try { UsbProtocol.decodeRequest(bytes, capability, wall); fail("Malformed request was accepted") }
        catch (error: UsbProtocol.Invalid) { if (reason != null) assertEquals(reason, error.reason) }
    }

    @Test fun validCommandsHaveNoSubmissionOrRawInputCapability() {
        for (code in 0..5) assertEquals(code, UsbProtocol.decodeRequest(request(code), capability, wall).operation.code)
        val plan = checkNotNull(UsbProtocol.decodeRequest(request(), capability, wall).plan)
        assertFalse(plan.autoSubmit)
        assertFalse(plan.waitForStock)
        assertEquals(1, plan.quantity)
        assertEquals(88000L, plan.maxTotalCents)
        assertEquals(wall, plan.startAtMs)
        assertEquals(wall + 120000, plan.endAtMs)
        assertTrue(plan.buyerLabels.isEmpty())
        assertEquals("UsbRequest(RUN, redacted)", UsbProtocol.decodeRequest(request(buyers = listOf("测试甲")), capability, wall).toString())
    }
    @Test fun authenticationVersionOperationAndTrailingBytesAreStrict() {
        rejects(request(cap = "b".repeat(64)), "capability_invalid")
        rejects(request(version = 2), "protocol_version_invalid")
        rejects(request(operation = 6), "operation_invalid")
        rejects(request(trailing = true), "unexpected_request_fields")
        val good = request()
        for (length in good.indices) rejects(good.copyOf(length))
        rejects(ByteArray(UsbProtocol.MAX_FRAME_BYTES + 1))
    }
    @Test fun invalidTargetsBudgetsBuyersAndTimesNeverBecomePlans() {
        rejects(request(quantity = 0)); rejects(request(quantity = 21))
        rejects(request(price = 0)); rejects(request(budget = 88001)); rejects(request(budget = 87999))
        rejects(request(timeout = 9)); rejects(request(timeout = 601))
        rejects(request(start = wall - 1)); rejects(request(start = wall + 24 * 3600000L + 1))
        rejects(request(operation = 4, start = wall + 1000))
        rejects(request(buyers = listOf("甲", "乙")))
        rejects(request(quantity = 2, budget = 176000, buyers = listOf("甲", "甲")))
        rejects(request(buyers = listOf(" "))); rejects(request(buyers = listOf("甲\n乙")))
        assertEquals(listOf("测试甲"), UsbProtocol.decodeRequest(request(buyers = listOf("测试甲")), capability, wall).plan!!.buyerLabels)
    }
    @Test fun framedTransportAndControlsRejectOversizeTruncationAndReplay() {
        val bytes = ByteArrayOutputStream()
        UsbProtocol.writeFrame(bytes, request(1))
        assertArrayEquals(request(1), UsbProtocol.readFrame(ByteArrayInputStream(bytes.toByteArray())))
        for (size in listOf(-1, 0, UsbProtocol.MAX_FRAME_BYTES + 1)) {
            val invalid = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
            try { UsbProtocol.readFrame(ByteArrayInputStream(invalid)); fail("Invalid frame length accepted") }
            catch (_: UsbProtocol.Invalid) { }
        }
        fun control(kind: Int, sequence: Long) = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeByte(kind); writeLong(sequence) } }.toByteArray()
        assertEquals(UsbProtocol.Control(UsbProtocol.HEARTBEAT, 2), UsbProtocol.decodeControl(control(1, 2), 1))
        assertEquals(UsbProtocol.Control(UsbProtocol.CANCEL, 3), UsbProtocol.decodeControl(control(2, 3), 2))
        for (bad in listOf(control(1, 1), control(1, 0), control(1, -1), control(3, 2), byteArrayOf(1))) {
            try { UsbProtocol.decodeControl(bad, 1); fail("Invalid/replayed control accepted") }
            catch (_: UsbProtocol.Invalid) { }
        }
    }
}

class UsbLeaseTest {
    @Test fun unconnectedOrDisconnectedLeaseCannotKeepAnExecutorAlive() {
        val lease = UsbLease(100)
        assertTrue(lease.isLive(100))
        assertFalse(lease.heartbeat(101))
        assertTrue(lease.connect(200))
        assertFalse(lease.connect(201))
        assertTrue(lease.heartbeat(250))
        assertTrue(lease.isLive(3249))
        assertFalse(lease.isLive(3250))
        assertEquals("usb_lease_expired", lease.reason)
        assertFalse(lease.heartbeat(3251))
        val absent = UsbLease(100)
        assertFalse(absent.isLive(10100))
        assertEquals("usb_connection_timeout", absent.reason)
    }
    @Test fun stopAndClockRegressionAreIrreversible() {
        val stopped = UsbLease(100)
        assertTrue(stopped.connect(110))
        stopped.cancel("stopped_by_user")
        assertFalse(stopped.isLive(120)); assertFalse(stopped.heartbeat(120))
        stopped.cancel("another_reason")
        assertEquals("stopped_by_user", stopped.reason)
        val clock = UsbLease(100)
        assertFalse(clock.isLive(99))
        assertFalse(clock.connect(101))
        assertEquals("clock_regressed", clock.reason)
    }
}
