package local.tickets.companion

import androidx.test.platform.app.InstrumentationRegistry
import local.tickets.companion.storage.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android filesystem checks in isolated test-only storage; never clicks or creates an order. */
class SubmissionFenceDeviceTest {
    private lateinit var directory: File
    private val task = "a".repeat(32)
    private val attempt = "b".repeat(32)
    private val digest = "c".repeat(64)

    @Before fun isolatedNamespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Device tests must use the disposable verification application",
            "local.tickets.companion.verification", context.packageName)
        directory = File(context.noBackupFilesDir, "fence-verification-${UUID.randomUUID()}")
    }

    @After fun cleanupOnlyOwnedTestNamespace() {
        if (::directory.isInitialized && directory.name.startsWith("fence-verification-")) {
            directory.walkBottomUp().forEach { check(it.delete() || !it.exists()) }
        }
    }

    private fun store() = SubmissionFence(directory, AndroidDirectorySync)

    @Test fun intentAndHandoffSurviveReopeningAndStillBlockNewSubmission() {
        val first = store()
        assertFalse(first.readState().blocked)
        first.withCaller { caller ->
            caller.beginSubmission(task, attempt, digest, System.currentTimeMillis())
            assertTrue(store().readState().blocked)
            assertThrows(CallerActiveException::class.java) { store().acknowledge(attempt, true) }
            caller.markAlipayHandoff(attempt)
        }
        val reopened = store()
        assertEquals(OrderState.ALIPAY_HANDOFF, reopened.readState().pending!!.orderState)
        assertThrows(FenceBlockedException::class.java) {
            reopened.withCaller { it.beginSubmission("d".repeat(32), "e".repeat(32), digest, System.currentTimeMillis()) }
        }
        assertThrows(IllegalArgumentException::class.java) { reopened.acknowledge(attempt, false) }
        assertThrows(StaleAttemptException::class.java) { reopened.acknowledge("f".repeat(32), true) }
        reopened.acknowledge(attempt, true)
        assertFalse(store().readState().blocked)
        assertEquals(1, store().readState().records.size)
    }

    @Test fun corruptEvidenceFailsClosedOnAndroidFilesystem() {
        store().withCaller { it.beginSubmission(task, attempt, digest, System.currentTimeMillis()) }
        File(directory, "records").appendText("invalid-test-tail")
        assertThrows(FenceStorageException::class.java) { store().readState() }
        assertThrows(FenceStorageException::class.java) { store().acknowledge(attempt, true) }
    }

    @Test fun structuralDiagnosticsContainNoPageTextAndRespectBounds() {
        val audit = NativeAuditStructure()
        audit.observe(null, "cn.damai:id/sku_item", "android.widget.TextView",
            true, true, false, false, false, false)
        audit.observe(0, "cn.damai:id/姓名1234", "private.dynamic.Class",
            true, true, false, false, false, false)
        val result = audit.json()
        assertEquals("cn.damai:id/sku_item", result.getJSONArray("nodes").getJSONObject(0).getString("resourceId"))
        assertFalse(result.toString().contains("姓名"))
        assertFalse(result.toString().contains("private"))
        repeat(79) { audit.observe(null, null, null, true, true, false, false, false, false) }
        assertFalse(audit.json().getBoolean("complete"))
        assertEquals(0, audit.json().getJSONArray("nodes").length())
        val indicators = NativeAuditIndicators()
        indicators.observe("cn.damai:id/sku_item", "场次 票档", null, true, false, false, false)
        indicators.observeWindow(true, true, true, true)
        assertEquals(1, indicators.json().getInt("resourceIdNodeCount"))
        assertEquals(1, indicators.json().getInt("sessionLabelNodeCount"))
        assertEquals(1, indicators.json().getInt("tierLabelNodeCount"))
        assertEquals(1, indicators.json().getInt("inspectedWindowMatchCount"))
    }

    @Test fun oneUseLocalPermitWorksOnDeviceWithoutEnablingCapture() {
        var now = 1000L
        val permit = NativeAuditPermit { now }
        assertFalse(permit.consume())
        permit.arm()
        assertTrue(permit.consume())
        assertFalse(permit.consume())
        permit.arm()
        now += 120_000
        assertFalse(permit.consume())
    }
}
