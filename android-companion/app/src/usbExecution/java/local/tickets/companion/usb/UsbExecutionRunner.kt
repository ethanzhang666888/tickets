package local.tickets.companion.usb

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production USB entry, not a JUnit runner. It receives one authenticated in-memory task,
 * never raw input commands. UiAutomation authority exists only in the ADB-created session.
 */
class UsbExecutionRunner : Instrumentation() {
    private var startup = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        startup = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val finished = AtomicBoolean(false)
        val lease = UsbLease(SystemClock.elapsedRealtime())
        var server: LocalServerSocket? = null
        var socket: LocalSocket? = null
        var automation: UiAutomation? = null
        var originalInfo: android.accessibilityservice.AccessibilityServiceInfo? = null
        val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "usb-session-lease").apply { isDaemon = true } }
        val writerGate = Any()
        val runId = startup.getString("runId").orEmpty()
        val capability = startup.getString("capability").orEmpty()
        var terminalReason = "usb_execution_failed"
        fun finishOnce(reason: String) {
            if (finished.compareAndSet(false, true)) finish(Activity.RESULT_OK, Bundle().apply { putString("reason", reason) })
        }
        fun emit(type: String, data: JSONObject) {
            val peer = socket ?: throw UsbProtocol.Invalid("usb_disconnected")
            val message = JSONObject().put("protocol", UsbProtocol.VERSION).put("runId", runId).put("type", type).put("data", data)
            synchronized(writerGate) { UsbProtocol.writeFrame(peer.outputStream, message.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            if (targetContext.packageName != "local.tickets.companion.usb" || !Regex("[0-9a-f]{32}").matches(runId) ||
                !Regex("[0-9a-f]{64}").matches(capability) || startup.keySet() != setOf("runId", "capability"))
                throw UsbProtocol.Invalid("startup_invalid")
            // No UI access is possible through an ordinary application-started instrumentation
            // session without the ADB-provided automation connection.
            val connectedAutomation = checkNotNull(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
            automation = connectedAutomation
            originalInfo = connectedAutomation.serviceInfo
            val listener = LocalServerSocket("tickets.usb.$runId")
            server = listener
            watchdog.scheduleWithFixedDelay({
                if (!finished.get() && !lease.isLive(SystemClock.elapsedRealtime())) {
                    runCatching { socket?.close() }
                    runCatching { server?.close() }
                    finishOnce(lease.reason)
                }
            }, 100, 100, TimeUnit.MILLISECONDS)
            sendStatus(0, Bundle().apply { putString("stream", "\nTICKETS_USB_READY\n") })
            val peer = listener.accept()
            socket = peer
            val uid = peer.peerCredentials.uid
            if (uid != 2000 && uid != 0) throw UsbProtocol.Invalid("usb_peer_not_authorized") // adbd shell/root transport only
            peer.soTimeout = UsbProtocol.CONNECT_MS.toInt()
            val hello = JSONObject().put("protocol", UsbProtocol.VERSION).put("package", targetContext.packageName)
                .put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT)
            val greeting = "hello|${UsbProtocol.VERSION}|$runId|${targetContext.packageName}|${Process.myPid()}|${Build.VERSION.SDK_INT}"
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
                init(javax.crypto.spec.SecretKeySpec(capability.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
            }
            hello.put("proof", mac.doFinal(greeting.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
            emit("hello", hello)
            val request = UsbProtocol.decodeRequest(UsbProtocol.readFrame(peer.inputStream), capability, System.currentTimeMillis())
            if (!lease.connect(SystemClock.elapsedRealtime())) throw UsbProtocol.Invalid("usb_connection_timeout")
            peer.soTimeout = UsbProtocol.LEASE_MS.toInt()
            Thread({
                var sequence = 0L
                try {
                    while (lease.isLive(SystemClock.elapsedRealtime()) && !finished.get()) {
                        val control = UsbProtocol.decodeControl(UsbProtocol.readFrame(peer.inputStream), sequence)
                        sequence = control.sequence
                        if (control.kind == UsbProtocol.CANCEL) { lease.cancel("stopped_by_user"); break }
                        if (!lease.heartbeat(SystemClock.elapsedRealtime())) break
                    }
                } catch (_: Exception) { if (!finished.get()) lease.cancel("usb_disconnected") }
            }, "usb-session-control").apply { isDaemon = true; start() }
            if (request.operation != UsbProtocol.Operation.PROBE) handoffNormalCompanion(lease)
            val result = if (request.operation == UsbProtocol.Operation.PROBE) JSONObject()
                .put("phase", "ready").put("reason", "usb_executor_ready").put("actions", 0)
                .put("automaticSubmissionSupported", false).put("attendeeIdentityExported", false)
                else UsbDeviceExecutor(this, connectedAutomation, lease) { update -> emit("progress", update) }.execute(request)
            terminalReason = result.optString("reason", "completed")
            emit("complete", result)
        } catch (error: UsbProtocol.Invalid) {
            terminalReason = error.reason
            runCatching { emit("error", JSONObject().put("reason", terminalReason)) }
        } catch (_: Exception) {
            terminalReason = if (lease.isLive(SystemClock.elapsedRealtime())) "usb_execution_failed" else lease.reason
            runCatching { emit("error", JSONObject().put("reason", terminalReason)) }
        } finally {
            lease.cancel(terminalReason)
            watchdog.shutdownNow()
            runCatching { socket?.close() }
            runCatching { server?.close() }
            runCatching { automation?.setOnAccessibilityEventListener(null) }
            runCatching { originalInfo?.let { automation?.serviceInfo = it } }
            finishOnce(terminalReason)
        }
    }

    /** Stop-only, signature-protected acknowledgement; never open the companion's private UI. */
    private fun handoffNormalCompanion(lease: UsbLease) {
        val own = "local.tickets.companion"
        val component = ComponentName(own, "$own.execution.ExecutionStopReceiver")
        val manager = targetContext.packageManager
        val installed = try { manager.getApplicationInfo(own, 0) } catch (_: PackageManager.NameNotFoundException) { return }
        if (!installed.enabled || installed.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0) return
        val accessibility = targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val enabled = accessibility.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == own && it.resolveInfo.serviceInfo.name == "$own.InspectorAccessibilityService"
        }
        if (!enabled) return // Without its service the normal preview cannot issue cross-app actions.
        if (manager.checkSignatures(own, targetContext.packageName) != PackageManager.SIGNATURE_MATCH)
            throw UsbProtocol.Invalid("normal_companion_signature_mismatch")
        val receiver = try { manager.getReceiverInfo(component, PackageManager.GET_META_DATA) }
            catch (_: PackageManager.NameNotFoundException) { throw UsbProtocol.Invalid("normal_companion_update_required") }
        if (!receiver.exported || receiver.permission != "local.tickets.companion.permission.STOP_LOCAL_EXECUTION" ||
            receiver.metaData?.getInt("local.tickets.usb.handoffVersion") != 1)
            throw UsbProtocol.Invalid("normal_companion_update_required")
        val stopped = CountDownLatch(1)
        var accepted = false
        targetContext.sendOrderedBroadcast(Intent("local.tickets.companion.USB_HANDOFF").setComponent(component), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { accepted = resultCode == Activity.RESULT_OK; stopped.countDown() }
            }, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
        if (!stopped.await(3, TimeUnit.SECONDS) || !accepted || !lease.isLive(SystemClock.elapsedRealtime()))
            throw UsbProtocol.Invalid("normal_execution_handoff_unconfirmed")
    }
}
