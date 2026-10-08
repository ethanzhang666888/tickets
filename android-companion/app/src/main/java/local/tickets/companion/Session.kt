package local.tickets.companion

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

object Session {
    val main = Handler(Looper.getMainLooper())
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val enrollment = Enrollment({ SystemClock.elapsedRealtime() })
    private val nativeAuditPermit = NativeAuditPermit { SystemClock.elapsedRealtime() }
    fun armNativeAudit() {
        requireMainThread()
        local.tickets.companion.execution.LocalExecutionRuntime.stop("native_audit_requested")
        capture?.stopSafely("native_audit_only")
        nativeAuditIndicators = NativeAuditIndicators()
        nativeAuditPermit.arm()
    }
    fun revokeNativeAudit() { requireMainThread(); nativeAuditPermit.revoke() }
    private fun requireMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun generatePairingCode() { requireMainThread(); enrollment.issue() }
    fun pairingCode(): String? { requireMainThread(); return enrollment.activeCode() }
    fun revokePairingCode() { requireMainThread(); enrollment.revoke() }
    fun pair(attempt: PairAttempt): JSONObject {
        requireMainThread()
        return if (enrollment.consume(attempt.candidate, attempt.validEnvelope)) envelope().put("ok", true).put("token", token)
        else envelope().put("ok", false).put("error", "pairing_failed")
    }
    @Volatile var captureDiagnostics = CaptureDiagnostics()
    // Retain only fixed counters/categories from the scan that stopped capture,
    // even when a subsequent native-only inspection succeeds. Reset on new consent.
    @Volatile var lastIncompleteScan = SafeSummary()
    @Volatile var nativeAuditIndicators = NativeAuditIndicators()
    @Volatile var summary = SafeSummary()
    @Volatile var captureState = "not_granted"
    @Volatile var bridgeState = "not_started"
    @Volatile var lastError = "none"
    @Volatile var testClickCount = 0
    @Volatile var syntheticVisible = false
    @Volatile var capture: CaptureService? = null
    @Volatile var accessibility: InspectorAccessibilityService? = null
    fun locked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun envelope(): JSONObject = JSONObject().put("protocolVersion", 1).put("stage", "feasibility").put("executionSupported", false)
    fun status(): JSONObject = envelope().put("ok", true)
        .put("accessibilityConnected", accessibility != null)
        .put("captureState", captureState).put("bridgeState", bridgeState)
        .put("error", lastError).put("testClickCount", testClickCount).put("summary", summary.json())
        .put("captureDiagnostics", captureDiagnostics.json())
        .put("lastIncompleteScan", lastIncompleteScan.json())
        .put("nativeAudit", nativeAuditIndicators.json())
        .put("nativeStructure", nativeAuditIndicators.structure.json())
        .put("localExecution", local.tickets.companion.execution.LocalExecutionRuntime.status())
    fun capabilities(): JSONObject = envelope().put("ok", true)
        .put("actions", JSONArray(Protocol.actions.toList()))
        .put("nativeInspection", true).put("bundledChineseOcr", true)
        .put("captureConsentRequired", true).put("testClickLocalOnly", true)
        .put("maxRequestBytes", Protocol.MAX_BYTES)
        .put("localNativeExecutionSupported", true).put("remoteExecutionSupported", false)
        .put("automaticSubmissionSupported", false).put("selfDrawnExecutionSupported", false)
        .put("enrollment", JSONObject().put("action", "pair").put("codeDigits", 8)
            .put("validityMs", Enrollment.VALIDITY_MS).put("maxFailedAttempts", Enrollment.MAX_FAILURES))
    fun inspect(): JSONObject {
        if (local.tickets.companion.execution.LocalExecutionRuntime.isActive()) return envelope().put("ok", false).put("error", "local_execution_active")
        val inspector = accessibility ?: return envelope().put("ok", false).put("error", "accessibility_required")
        val result = inspector.inspect()
        if (result == null) return status().put("ok", false)
        summary = result
        // Capture/OCR is an explicit one-shot feasibility measurement, never a click fallback.
        capture?.requestCapture(result)
        return status()
    }
    fun nativeAudit(): JSONObject {
        requireMainThread()
        if (!nativeAuditPermit.consume()) return envelope().put("ok", false).put("error", "native_audit_not_armed")
        capture?.stopSafely("native_audit_only")
        nativeAuditIndicators = NativeAuditIndicators()
        lastIncompleteScan = SafeSummary()
        summary = SafeSummary(captureState = "native_audit_only")
        val result = accessibility?.inspectNativeAudit()
        if (result == null) {
            captureState = "native_audit_unavailable"
            lastError = "native_audit_unavailable"
            summary = SafeSummary(captureState = "native_audit_unavailable")
            return status().put("ok", false)
        }
        summary = result.copy(captureState = "native_audit_only")
        lastError = "none"
        captureState = "native_audit_only"
        return status()
    }
    fun stop() {
        local.tickets.companion.execution.LocalExecutionRuntime.stop()
        revokePairingCode()
        revokeNativeAudit()
        nativeAuditIndicators = NativeAuditIndicators()
        capture?.stopSafely("stopped_by_user")
        if (captureState == "native_audit_only") { captureState = "stopped_by_user"; lastError = "stopped_by_user" }
        summary = SafeSummary(captureState = "stopped_by_user")
    }
}
