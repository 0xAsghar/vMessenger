package ir.vmessenger.core.common.logging

fun interface LogSink {
    fun append(entry: LogEntry)
}
