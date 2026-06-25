package ir.vmessenger.core.common.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private const val MAX_ENTRIES = 5_000

    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun addSink(sink: LogSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestampUnixMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        sinks.forEach { sink ->
            runCatching { sink.append(entry) }
        }
    }

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)

    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun snapshotText(): String = synchronized(buffer) {
        buffer.joinToString(separator = "\n") { it.formatLine() }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
