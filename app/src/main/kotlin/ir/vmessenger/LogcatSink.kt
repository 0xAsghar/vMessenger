package ir.vmessenger

import android.util.Log
import ir.vmessenger.core.common.logging.LogEntry
import ir.vmessenger.core.common.logging.LogLevel
import ir.vmessenger.core.common.logging.LogSink

/**
 * Mirrors the in-app log to logcat under a single tag so `adb logcat -s vMessenger`
 * can follow a two-emulator run. Registered by debug builds only — release builds
 * must not leak peer hashes and network details to any app holding READ_LOGS.
 */
class LogcatSink : LogSink {
    override fun append(entry: LogEntry) {
        val message = "${entry.tag}: ${entry.message}"
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
    }

    private companion object {
        const val TAG = "vMessenger"
    }
}
