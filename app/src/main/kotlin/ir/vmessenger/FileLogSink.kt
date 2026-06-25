package ir.vmessenger

import android.content.Context
import ir.vmessenger.core.common.logging.LogEntry
import ir.vmessenger.core.common.logging.LogSink
import java.io.File
import java.io.FileOutputStream

class FileLogSink(
    context: Context,
    private val maxBytes: Long = 2L * 1024 * 1024,
) : LogSink {
    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val logFile = File(logDir, LOG_FILE_NAME)
    private val lock = Any()

    override fun append(entry: LogEntry) {
        synchronized(lock) {
            rotateIfNeeded()
            FileOutputStream(logFile, true).use { out ->
                out.write(entry.formatLine().toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
            }
        }
    }

    fun logFilePath(): String = logFile.absolutePath

    private fun rotateIfNeeded() {
        if (logFile.length() < maxBytes) return
        val rotated = File(logDir, "$LOG_FILE_NAME.old")
        if (rotated.exists()) {
            rotated.delete()
        }
        logFile.renameTo(rotated)
    }

    companion object {
        private const val LOG_FILE_NAME = "vmessenger.log"
    }
}
