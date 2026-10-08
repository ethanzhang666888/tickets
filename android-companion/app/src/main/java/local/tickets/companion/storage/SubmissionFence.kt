package local.tickets.companion.storage

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Must actually fsync the directory, or throw. A no-op implementation is unsafe. */
fun interface DirectorySync {
    @Throws(IOException::class)
    fun sync(directory: File)
}

enum class OrderState(val storedValue: String) {
    UNKNOWN("unknown"), ALIPAY_HANDOFF("alipay_handoff")
}

data class SubmissionRecord(
    val taskId: String,
    val attemptId: String,
    val planDigest: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val orderState: OrderState,
    val acknowledgedAtMillis: Long? = null,
)

data class FenceState(val records: List<SubmissionRecord>) {
    val pending: SubmissionRecord? get() = records.lastOrNull()?.takeIf { it.acknowledgedAtMillis == null }
    val blocked: Boolean get() = pending != null
}

class FenceStorageException(cause: Throwable) : IllegalStateException("Submission storage is unsafe; submission is prohibited", cause)
class FenceBlockedException : IllegalStateException("An unresolved submission, reused attempt ID, or full evidence store prohibits submission")
class CallerActiveException : IllegalStateException("Another caller or storage transaction is active; operation is prohibited")
class StaleAttemptException : IllegalStateException("Expected attempt does not match the current unresolved submission")

internal enum class WritePoint {
    AFTER_MARKER_SYNC, AFTER_PARTIAL_WRITE, AFTER_FILE_SYNC, AFTER_REPLACE, BEFORE_MARKER_REMOVAL,
}

/**
 * Standalone device-wide fence, NOT a purchase executor or a resume mechanism.
 *
 * Integration contract:
 * - All callers/processes must use ONE fixed, app-private, non-backed-up directory on local
 *   internal storage. Its parent must exist. Never delete/replace the directory, lock files,
 *   records, or transaction marker, and never choose a per-task namespace.
 * - Keep the complete execution (including its last possible submit action) inside withCaller.
 *   Dispatch a submit action ONLY immediately after beginSubmission returns successfully, once.
 *   readState, acquiring a caller session, or finding an old intent NEVER grants submission.
 * - Acknowledge only after explicit human order review. The operation lock enforces inactivity
 *   for participating callers. Android services/controllers must all honor this same contract.
 * - Any exception prohibits the attempted operation. Storage errors poison this instance;
 *   incomplete writes also leave a blocking marker, with no automatic recovery/clear API.
 * - Use a real directory fsync implementation (AndroidDirectorySync on Android). File contents
 *   are forced before atomic replacement, then directory metadata is synced. Unsupported
 *   atomic moves/fsync fail closed, with no non-atomic fallback.
 *
 * The bounded, checksummed ledger retains ALL acknowledgements and intents (256 attempts).
 * Capacity exhaustion blocks new intents; this component intentionally offers no pruning API.
 * No names, document text, images, authentication tokens, or arbitrary user strings are stored.
 * An app data wipe, external deletion, rollback/backup restore, or broken storage hardware is
 * outside this filesystem protocol; deployment must prevent those from silently resetting it.
 */
class SubmissionFence internal constructor(
    directory: File,
    private val directorySync: DirectorySync,
    private val writePoint: (WritePoint) -> Unit,
) {
    constructor(directory: File, directorySync: DirectorySync) : this(directory, directorySync, {})

    // Resolve inside guarded I/O rather than in the constructor, so resolution failure is unsafe.
    private val requestedDirectory = directory
    @Volatile private var failed = false

    fun readState(): FenceState = transaction { directory -> FenceState(load(directory)) }

    /** Nonblocking exclusive session, admitted only when clear; the handle cannot outlive [action]. */
    fun <T> withCaller(action: (Caller) -> T): T = locked("caller.lock") {
        if (readState().blocked) throw FenceBlockedException()
        val caller = Caller(Thread.currentThread())
        try { action(caller) } finally { caller.close() }
    }

    inner class Caller internal constructor(private val thread: Thread) {
        @Volatile private var open = true
        internal fun close() { open = false }
        private fun requireActive() {
            check(open && Thread.currentThread() === thread) { "Caller session has ended or belongs to another thread" }
        }

        /** The sole one-shot permission to dispatch; retries (including identical IDs) are blocked. */
        fun beginSubmission(taskId: String, attemptId: String, planDigest: String, timestampMillis: Long): SubmissionRecord {
            requireActive()
            requireId(taskId)
            requireId(attemptId)
            require(DIGEST.matches(planDigest)) { "Expected an opaque lowercase hexadecimal plan digest" }
            require(timestampMillis > 0) { "Expected a positive timestamp" }
            return transaction { directory ->
                val records = load(directory)
                if (records.any { it.acknowledgedAtMillis == null || it.attemptId == attemptId } || records.size >= MAX_RECORDS) {
                    throw FenceBlockedException()
                }
                val record = SubmissionRecord(taskId, attemptId, planDigest, timestampMillis, timestampMillis, OrderState.UNKNOWN)
                persist(directory, records + record)
                record
            }
        }

        /** Merely records the handoff; it does not acknowledge an order or release the fence. */
        fun markAlipayHandoff(expectedAttemptId: String): SubmissionRecord {
            requireActive()
            requireId(expectedAttemptId)
            return change(expectedAttemptId, resolving = false)
        }
    }

    /** Requires both an explicit true and the exact unresolved ID; active callers prevent this. */
    fun acknowledge(expectedAttemptId: String, acknowledged: Boolean): SubmissionRecord {
        requireId(expectedAttemptId)
        require(acknowledged) { "Explicit human acknowledgement is required" }
        return locked("caller.lock") { change(expectedAttemptId, resolving = true) }
    }

    private fun change(expectedAttemptId: String, resolving: Boolean): SubmissionRecord = transaction { directory ->
        val records = load(directory)
        val current = records.lastOrNull()
        if (current == null || current.attemptId != expectedAttemptId || current.acknowledgedAtMillis != null) {
            throw StaleAttemptException()
        }
        val updatedAt = maxOf(current.updatedAtMillis, System.currentTimeMillis())
        val updated = current.copy(
            updatedAtMillis = updatedAt,
            orderState = if (resolving) current.orderState else OrderState.ALIPAY_HANDOFF,
            acknowledgedAtMillis = if (resolving) updatedAt else null,
        )
        persist(directory, records.dropLast(1) + updated)
        updated
    }

    private fun <T> transaction(action: (File) -> T): T = locked("records.lock", action)

    private fun <T> guarded(action: () -> T): T {
        if (failed) throw FenceStorageException(IOException("This instance previously encountered unsafe storage"))
        try { return action() }
        catch (failure: IOException) { failed = true; throw FenceStorageException(failure) }
        catch (failure: SecurityException) { failed = true; throw FenceStorageException(failure) }
    }

    private fun <T> locked(name: String, action: (File) -> T): T = guarded {
        val directory = requestedDirectory.canonicalFile
        ensureDirectory(directory)
        val path = File(directory, name).toPath()
        // POSIX may release a process's locks when ANY descriptor for that inode closes.
        // Exclude local contenders before opening a second descriptor; file locks alone are
        // insufficient. Canonical paths also collapse directory symlink aliases.
        val local = LOCAL_LOCKS.computeIfAbsent(path.toString()) { Semaphore(1) }
        if (!local.tryAcquire()) throw CallerActiveException()
        try {
            val attributes = attributesOrNull(File(directory, name))
            if (attributes != null && !attributes.isRegularFile) throw IOException("Invalid lock file")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) throw CallerActiveException()
                lock.use {
                    channel.force(true)
                    directorySync.sync(directory)
                    action(directory)
                }
            }
        } finally { local.release() }
    }

    private fun ensureDirectory(directory: File) {
        if (attributesOrNull(directory) == null) {
            try { Files.createDirectory(directory.toPath()) }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* Another instance may have created it. */ }
        }
        if (attributesOrNull(directory)?.isDirectory != true) throw IOException("Invalid fence directory")
        // Always sync the parent, including a directory just created by another process.
        directorySync.sync(directory.parentFile ?: throw IOException("Fence requires a parent directory"))
    }

    private fun attributesOrNull(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun load(directory: File): List<SubmissionRecord> {
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (entry.fileName.toString() !in setOf("records", "records.lock", "caller.lock")) {
                    throw IOException("Incomplete transaction or unexpected fence file")
                }
            }
        }
        val ledger = File(directory, "records")
        val attributes = attributesOrNull(ledger) ?: return emptyList()
        if (!attributes.isRegularFile || attributes.size() !in 1..MAX_BYTES.toLong()) throw IOException("Invalid ledger size or type")
        val bytes = FileChannel.open(ledger.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(MAX_BYTES + 1)
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* Bounded even if size changes. */ }
            if (buffer.position() > MAX_BYTES) throw IOException("Oversized ledger")
            buffer.array().copyOf(buffer.position())
        }
        return decode(bytes)
    }

    private fun persist(directory: File, records: List<SubmissionRecord>) {
        val bytes = encode(records)
        if (bytes.size > MAX_BYTES) throw IOException("Ledger capacity exceeded")
        val marker = File(directory, "pending")
        val next = File(directory, "next")
        try {
            writeSynced(marker, "incomplete-v1\n".toByteArray(Charsets.US_ASCII))
            directorySync.sync(directory)
            writePoint(WritePoint.AFTER_MARKER_SYNC)
            writeSynced(next, bytes, splitWrite = true)
            writePoint(WritePoint.AFTER_FILE_SYNC)
            Files.move(next.toPath(), File(directory, "records").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            writePoint(WritePoint.AFTER_REPLACE)
            directorySync.sync(directory)
            writePoint(WritePoint.BEFORE_MARKER_REMOVAL)
            Files.delete(marker.toPath())
            directorySync.sync(directory)
        } catch (failure: Exception) {
            failed = true
            // In particular, restore the marker if the final deletion's directory fsync failed.
            // Never erase the old or new evidence, or repair any abandoned transaction on reopen.
            // A permanently failed disk can prevent even this best-effort poison write; no software
            // can promise durability when the OS/filesystem refuses all writes and syncs.
            try {
                if (attributesOrNull(marker) == null) writeSynced(marker, "incomplete-v1\n".toByteArray(Charsets.US_ASCII))
                directorySync.sync(directory)
            } catch (poisonFailure: Exception) { failure.addSuppressed(poisonFailure) }
            throw FenceStorageException(failure)
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray, splitWrite: Boolean = false) {
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            if (splitWrite) {
                buffer.limit(bytes.size / 2)
                while (buffer.hasRemaining()) channel.write(buffer)
                writePoint(WritePoint.AFTER_PARTIAL_WRITE)
                buffer.limit(bytes.size)
            }
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun encode(records: List<SubmissionRecord>): ByteArray {
        val payload = buildString {
            append("submission-fence-v1\n")
            for (record in records) {
                append(listOf(record.taskId, record.attemptId, record.planDigest, record.createdAtMillis,
                    record.updatedAtMillis, record.orderState.storedValue,
                    record.acknowledgedAtMillis?.let { "acknowledged:$it" } ?: "unresolved").joinToString("|"))
                append('\n')
            }
        }
        return (payload + "sha256:" + checksum(payload.toByteArray(Charsets.US_ASCII)) + "\n").toByteArray(Charsets.US_ASCII)
    }

    private fun decode(bytes: ByteArray): List<SubmissionRecord> {
        try {
            val text = String(bytes, Charsets.US_ASCII)
            val lines = text.split('\n')
            require(lines.size in 4..MAX_RECORDS + 3 && lines.first() == "submission-fence-v1" && lines.last().isEmpty())
            val records = lines.subList(1, lines.size - 2).map { line ->
                val fields = line.split('|')
                require(fields.size == 7)
                requireId(fields[0])
                requireId(fields[1])
                require(DIGEST.matches(fields[2]))
                val createdAt = fields[3].toLong()
                val updatedAt = fields[4].toLong()
                val state = OrderState.values().single { it.storedValue == fields[5] }
                val acknowledgedAt = when {
                    fields[6] == "unresolved" -> null
                    fields[6].startsWith("acknowledged:") -> fields[6].removePrefix("acknowledged:").toLong()
                    else -> throw IllegalArgumentException("Invalid acknowledgement")
                }
                require(createdAt > 0 && updatedAt >= createdAt)
                require(acknowledgedAt == null || acknowledgedAt == updatedAt)
                SubmissionRecord(fields[0], fields[1], fields[2], createdAt, updatedAt, state, acknowledgedAt)
            }
            require(records.map { it.attemptId }.distinct().size == records.size)
            require(records.dropLast(1).all { it.acknowledgedAtMillis != null })
            // Re-encoding checks the checksum AND rejects non-canonical text, extra data and keys.
            require(encode(records).contentEquals(bytes))
            return records
        } catch (failure: IllegalArgumentException) { throw IOException("Invalid submission ledger", failure) }
        catch (failure: NoSuchElementException) { throw IOException("Invalid submission ledger", failure) }
    }

    private fun checksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun requireId(value: String) {
        require(ID.matches(value)) { "Expected an opaque lowercase hexadecimal ID" }
    }

    private companion object {
        const val MAX_RECORDS = 256
        const val MAX_BYTES = 65536
        val ID = Regex("[0-9a-f]{32}")
        val DIGEST = Regex("[0-9a-f]{64}")
        // Keep entries stable: removing a released semaphore can split racing local callers.
        // Production uses one fixed namespace, hence two entries per application process.
        val LOCAL_LOCKS = ConcurrentHashMap<String, Semaphore>()
    }
}
