package ir.vmessenger.core.common.concurrency

import ir.vmessenger.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Handler for long-lived scopes: an uncaught throwable in one child is logged
 * under [tag] instead of taking the process down. Pair it with a
 * `SupervisorJob` so sibling coroutines keep running.
 */
fun loggingExceptionHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        AppLogger.error(tag, "uncaught: $throwable")
    }
