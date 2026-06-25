package ir.vmessenger.core.common.logging

data class LogEntry(
    val timestampUnixMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun formatLine(): String {
        val levelChar = when (level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        return "$timestampUnixMs $levelChar/$tag: $message"
    }
}
