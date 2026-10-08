package local.tickets.companion.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal val jvmDirectorySync = DirectorySync { directory ->
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

class SubmissionFenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val task = "a".repeat(32)
    private val attempt = "b".repeat(32)
    private val digest = "c".repeat(64)
    private fun directory() = File(temporary.root, "fence")
    private fun store(directory: File = directory()) = SubmissionFence(directory, jvmDirectorySync)
    private fun begin(fence: SubmissionFence, id: String = attempt) = fence.withCaller {
        it.beginSubmission(task, id, digest, 1234L)
    }
    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got $failure", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun durableIntentSurvivesReopenAndBlocksEveryTaskIncludingSameAttempt() {
        val record = begin(store())
        assertEquals(task, record.taskId)
        assertEquals(attempt, record.attemptId)
        assertEquals(digest, record.planDigest)
        assertEquals(1234L, record.createdAtMillis)
        assertEquals(OrderState.UNKNOWN, record.orderState)
        val reopened = store()
        assertEquals(record, reopened.readState().pending)
        fails<FenceBlockedException> { begin(reopened) }
        fails<FenceBlockedException> {
            reopened.withCaller { it.beginSubmission("d".repeat(32), "e".repeat(32), digest, Long.MAX_VALUE) }
        }
    }

    @Test fun unresolvedIntentRejectsNewCallerBeforeItsBodyRuns() {
        begin(store())
        var entered = false
        fails<FenceBlockedException> { store().withCaller { entered = true } }
        assertFalse(entered)
    }

    @Test fun handoffRemainsUnresolvedAfterRestart() {
        val fence = store()
        fence.withCaller {
            it.beginSubmission(task, attempt, digest, 1234)
            it.markAlipayHandoff(attempt)
        }
        assertEquals(OrderState.ALIPAY_HANDOFF, store().readState().pending!!.orderState)
        fails<FenceBlockedException> { begin(store(), "d".repeat(32)) }
    }

    @Test fun explicitMatchingAcknowledgementPreservesHistoryAndAllowsOnlyANewAttempt() {
        val fence = store()
        begin(fence)
        fails<IllegalArgumentException> { fence.acknowledge(attempt, false) }
        fails<StaleAttemptException> { fence.acknowledge("d".repeat(32), true) }
        assertTrue(fence.readState().blocked)
        fence.acknowledge(attempt, true)
        val acknowledged = store().readState()
        assertFalse(acknowledged.blocked)
        assertEquals(attempt, acknowledged.records.single().attemptId)
        assertNotNull(acknowledged.records.single().acknowledgedAtMillis)
        fails<StaleAttemptException> { fence.acknowledge(attempt, true) }
        fails<FenceBlockedException> { begin(fence) }
        begin(fence, "d".repeat(32))
        fails<StaleAttemptException> { fence.acknowledge(attempt, true) }
        assertEquals(2, store().readState().records.size)
        assertEquals("d".repeat(32), store().readState().pending!!.attemptId)
    }

    @Test fun acknowledgementRequiresNoActiveCallerAcrossInstances() {
        val fence = store()
        val other = store()
        fence.withCaller {
            it.beginSubmission(task, attempt, digest, 1234)
            fails<CallerActiveException> { other.acknowledge(attempt, true) }
            fails<CallerActiveException> { fence.acknowledge(attempt, true) }
            fails<CallerActiveException> { other.withCaller { } }
        }
        other.acknowledge(attempt, true)
        assertFalse(other.readState().blocked)
    }

    @Test fun callerHandleCannotEscapeOrRunOnAnotherThread() {
        val fence = store()
        lateinit var escaped: SubmissionFence.Caller
        fence.withCaller {
            escaped = it
            val executor = Executors.newSingleThreadExecutor()
            try {
                val failure = executor.submit(Callable {
                    fails<IllegalStateException> { it.beginSubmission(task, attempt, digest, 1234) }
                }).get(10, TimeUnit.SECONDS)
                assertNotNull(failure)
            } finally { executor.shutdownNow() }
        }
        fails<IllegalStateException> { escaped.beginSubmission(task, attempt, digest, 1234) }
        assertFalse(fence.readState().blocked)
    }

    @Test fun validatesOpaqueIdsDigestAndTimestampWithoutWritingSensitiveInput() {
        val fence = store()
        for (invalid in listOf("", "a".repeat(31), "A".repeat(32), "x".repeat(32), "姓名身份证")) {
            fence.withCaller {
                fails<IllegalArgumentException> { it.beginSubmission(invalid, attempt, digest, 1234) }
                fails<IllegalArgumentException> { it.beginSubmission(task, invalid, digest, 1234) }
                fails<IllegalArgumentException> { it.markAlipayHandoff(invalid) }
            }
            fails<IllegalArgumentException> { fence.acknowledge(invalid, true) }
        }
        fence.withCaller {
            for (invalid in listOf("c".repeat(63), "C".repeat(64), "g".repeat(64))) {
                fails<IllegalArgumentException> { it.beginSubmission(task, attempt, invalid, 1234) }
            }
            fails<IllegalArgumentException> { it.beginSubmission(task, attempt, digest, 0) }
            fails<IllegalArgumentException> { it.beginSubmission(task, attempt, digest, -1) }
        }
        assertFalse(store().readState().blocked)
        fence.withCaller {
            it.beginSubmission(task, attempt, digest, 1234)
            fails<StaleAttemptException> { it.markAlipayHandoff("d".repeat(32)) }
        }
    }

    @Test fun corruptTruncatedOversizedAndNonRegularRecordsFailClosedWithoutRepair() {
        for ((index, bytes) in listOf(byteArrayOf(), "garbage".toByteArray(), ByteArray(65537) { 65 }).withIndex()) {
            val directory = File(temporary.root, "bad-$index")
            begin(store(directory))
            val ledger = File(directory, "records")
            ledger.writeBytes(bytes)
            fails<FenceStorageException> { store(directory).readState() }
            fails<FenceStorageException> { begin(store(directory)) }
            assertArrayEquals(bytes, ledger.readBytes())
        }
        val directory = File(temporary.root, "non-regular")
        begin(store(directory))
        assertTrue(File(directory, "records").delete())
        assertTrue(File(directory, "records").mkdir())
        fails<FenceStorageException> { store(directory).readState() }
    }

    @Test fun validLookingSingleByteDamageAndTruncationFailClosed() {
        val fence = store()
        begin(fence)
        val ledger = File(directory(), "records")
        val original = ledger.readBytes()
        ledger.writeBytes(original.copyOf(original.size - 1))
        fails<FenceStorageException> { store().readState() }
        val changed = original.clone()
        val offset = String(original, Charsets.US_ASCII).indexOf(task)
        assertTrue(offset >= 0)
        changed[offset] = 'd'.code.toByte()
        ledger.writeBytes(changed)
        fails<FenceStorageException> { store().readState() }
    }

    @Test fun everyInterruptedIntentWriteFailsClosedOnSameInstanceAndReopen() {
        for (point in WritePoint.values()) {
            val directory = File(temporary.root, "intent-${point.name}")
            val fence = SubmissionFence(directory, jvmDirectorySync) {
                if (it == point) throw IOException("injected")
            }
            fails<FenceStorageException> { begin(fence) }
            fails<FenceStorageException> { fence.readState() }
            fails<FenceStorageException> { store(directory).readState() }
            fails<FenceStorageException> { begin(store(directory), "d".repeat(32)) }
        }
    }

    @Test fun everyInterruptedAcknowledgementPreservesEvidenceAndBlocksReopen() {
        for (point in WritePoint.values()) {
            val directory = File(temporary.root, "ack-${point.name}")
            begin(store(directory))
            val fence = SubmissionFence(directory, jvmDirectorySync) {
                if (it == point) throw IOException("injected")
            }
            fails<FenceStorageException> { fence.acknowledge(attempt, true) }
            assertTrue(File(directory, "records").readText().contains(attempt))
            fails<FenceStorageException> { store(directory).readState() }
        }
    }

    @Test fun finalDirectorySyncFailureRestoresBlockingMarker() {
        val directory = directory()
        begin(store())
        var fail = true
        val sync = DirectorySync {
            if (fail && !File(directory, "pending").exists() &&
                File(directory, "records").readText().contains("acknowledged")) {
                fail = false
                throw IOException("final directory sync")
            }
            jvmDirectorySync.sync(it)
        }
        fails<FenceStorageException> { SubmissionFence(directory, sync).acknowledge(attempt, true) }
        assertFalse(fail)
        fails<FenceStorageException> { store().readState() }
    }

    @Test fun unavailableDirectoryOrSyncNeverGrantsSubmission() {
        val notDirectory = temporary.newFile("file")
        fails<FenceStorageException> { begin(store(notDirectory)) }
        val fence = SubmissionFence(directory(), DirectorySync { throw IOException("unavailable") })
        fails<FenceStorageException> { begin(fence) }
        fails<FenceStorageException> { fence.readState() }
    }

    @Test fun simultaneousInstancesCannotBothClaim() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { index ->
                executor.submit(Callable {
                    start.await()
                    try { begin(store(), if (index == 0) attempt else "d".repeat(32)); true }
                    catch (_: FenceBlockedException) { false }
                    catch (_: CallerActiveException) { false }
                })
            }
            start.countDown()
            assertEquals(1, futures.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, store().readState().records.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun unreadableLedgerFailsClosed() {
        begin(store())
        val path = File(directory(), "records").toPath()
        val permissions = java.nio.file.Files.getPosixFilePermissions(path)
        try {
            java.nio.file.Files.setPosixFilePermissions(path, emptySet())
            fails<FenceStorageException> { store().readState() }
            fails<FenceStorageException> { begin(store(), "d".repeat(32)) }
        } finally { java.nio.file.Files.setPosixFilePermissions(path, permissions) }
    }

    @Test fun conflictingOrDuplicateRecordsFailEvenWithAValidChecksum() {
        val directory = directory()
        begin(store())
        val ledger = File(directory, "records")
        val original = ledger.readLines()[1]
        val other = original.replace(attempt, "d".repeat(32))
        for (lines in listOf(listOf(original, other), listOf(original, original))) {
            val payload = "submission-fence-v1\n" + lines.joinToString("\n") + "\n"
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            ledger.writeText(payload + "sha256:" + hash + "\n")
            fails<FenceStorageException> { store().readState() }
            fails<FenceStorageException> { store().acknowledge(attempt, true) }
        }
    }

    @Test fun capacityNeverDiscardsAcknowledgedEvidence() {
        begin(store())
        store().acknowledge(attempt, true)
        val ledger = File(directory(), "records")
        val original = ledger.readLines()[1]
        val lines = (1..256).map { original.replace(attempt, it.toString(16).padStart(32, '0')) }
        val payload = "submission-fence-v1\n" + lines.joinToString("\n") + "\n"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        ledger.writeText(payload + "sha256:" + hash + "\n")
        val evidence = ledger.readBytes()
        assertEquals(256, store().readState().records.size)
        fails<FenceBlockedException> { begin(store()) }
        assertArrayEquals(evidence, ledger.readBytes())
    }

    @Test fun processDeathAfterClaimLeavesIntentAndNeverResumes() {
        val child = process(directory(), attempt, "crash-after-begin")
        try {
            assertEquals("READY", child.inputStream.bufferedReader().readLine())
            child.outputStream.write(10)
            child.outputStream.flush()
            assertTrue(child.waitFor(15, TimeUnit.SECONDS))
            assertEquals(23, child.exitValue())
            assertEquals(attempt, store().readState().pending!!.attemptId)
            fails<FenceBlockedException> { begin(store()) }
        } finally { child.destroyForcibly() }
    }

    @Test fun abruptProcessDeathDuringIntentAndAcknowledgementFailsClosed() {
        for (mode in listOf("crash-partial-intent", "crash-partial-ack")) {
            val directory = File(temporary.root, mode)
            if (mode == "crash-partial-ack") begin(store(directory))
            val child = process(directory, attempt, mode)
            try {
                assertEquals("READY", child.inputStream.bufferedReader().readLine())
                child.outputStream.write(10)
                child.outputStream.flush()
                assertTrue(child.waitFor(15, TimeUnit.SECONDS))
                assertEquals(23, child.exitValue())
                fails<FenceStorageException> { store(directory).readState() }
                fails<FenceStorageException> { begin(store(directory)) }
                if (mode == "crash-partial-ack") {
                    assertTrue(File(directory, "records").readText().contains(attempt))
                }
            } finally { child.destroyForcibly() }
        }
    }

    private fun process(directory: File, id: String, mode: String = "begin"): Process {
        val classpath = listOf(SubmissionFence::class.java, FenceProcess::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct()
            .joinToString(File.pathSeparator)
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath, FenceProcess::class.java.name, directory.absolutePath, id, mode)
            .redirectErrorStream(true).start()
    }

    @Test fun localContentionCannotReleaseAnotherInstancesOperatingSystemLock() {
        val fence = store()
        fence.withCaller {
            // POSIX locks can be dropped by closing a second descriptor for the same inode.
            fails<CallerActiveException> { store().withCaller { } }
            val contender = process(directory(), attempt)
            try {
                val reader = contender.inputStream.bufferedReader()
                assertEquals("READY", reader.readLine())
                contender.outputStream.write(10)
                contender.outputStream.flush()
                assertTrue(contender.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, contender.exitValue())
                assertEquals("BLOCKED", reader.readText().trim())
            } finally { contender.destroyForcibly() }
        }
        assertFalse(fence.readState().blocked)
    }

    @Test fun separateProcessesCannotBothClaim() {
        val processes = listOf(attempt, "d".repeat(32)).map { id -> process(directory(), id) }
        try {
            val readers = processes.map { it.inputStream.bufferedReader() }
            readers.forEach { assertEquals("READY", it.readLine()) }
            processes.forEach { it.outputStream.write(10); it.outputStream.flush() }
            processes.forEach { assertTrue(it.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, it.exitValue()) }
            assertEquals(listOf("BLOCKED", "CLAIMED"), readers.map { it.readText().trim() }.sorted())
            assertTrue(store().readState().blocked)
        } finally { processes.forEach { it.destroyForcibly() } }
    }
}

/** Separate JVM, real OS locks; no Android/device code or production data. */
object FenceProcess {
    @JvmStatic fun main(args: Array<String>) {
        println("READY")
        System.`in`.read()
        try {
            val mode = args.getOrElse(2) { "begin" }
            val fence = SubmissionFence(File(args[0]), jvmDirectorySync) {
                if (mode.startsWith("crash-partial") && it == WritePoint.AFTER_PARTIAL_WRITE) Runtime.getRuntime().halt(23)
            }
            if (mode == "crash-partial-ack") {
                fence.acknowledge(args[1], true)
            } else {
                fence.withCaller {
                    it.beginSubmission("a".repeat(32), args[1], "c".repeat(64), 1234)
                    if (mode == "crash-after-begin") Runtime.getRuntime().halt(23)
                }
            }
            println("CLAIMED")
        } catch (_: CallerActiveException) { println("BLOCKED") }
        catch (_: FenceBlockedException) { println("BLOCKED") }
    }
}
