package local.tickets.companion.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Android API 29+ directory metadata durability. JVM tests inject a real NIO fsync instead. */
object AndroidDirectorySync : DirectorySync {
    override fun sync(directory: File) {
        try {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            var failure: Throwable? = null
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw IOException("Expected a directory")
                Os.fsync(descriptor)
            }
            catch (error: Throwable) { failure = error; throw error }
            finally {
                try { Os.close(descriptor) }
                catch (error: ErrnoException) {
                    if (failure != null) failure.addSuppressed(error) else throw error
                }
            }
        } catch (error: ErrnoException) {
            throw IOException("Unable to durably sync submission fence directory", error)
        }
    }
}
