package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import ir.vmessenger.network.discovery.DhtDiscoveryProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps this device resolvable by re-publishing its endpoint record before the
 * DHT copy expires.
 *
 * A published record lives for [DhtDiscoveryProvider.DEFAULT_TTL_MS] (the node
 * enforces its own bound on top of that), so publishing only at start meant a
 * peer that had no cached endpoint could not resolve us after twenty minutes —
 * first contact silently failed until the app was restarted. The loop therefore
 * re-announces every [PERIOD_MS] (half the TTL, so one lost publish is not fatal)
 * through the same [PublishNetworkEndpointsUseCase] the start path uses, which
 * keeps the publish sequence monotonic and preserves the
 * "store rejected -> bump the sequence and retry" recovery.
 *
 * [announceNow] re-announces immediately (connectivity came back, or a relay /
 * bootstrap recovery changed the endpoints we advertise). A failed attempt backs
 * off from [FAILURE_BACKOFF_MS], doubling up to [PERIOD_MS], so an offline device
 * never tight-loops. Nothing is published while no identity exists, and every
 * attempt logs exactly one line.
 */
@Singleton
class EndpointAnnouncer @Inject constructor(
    private val publishNetworkEndpointsUseCase: PublishNetworkEndpointsUseCase,
    private val selfIdentityCache: SelfIdentityCache,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** The endpoint set the start path last published; re-announced verbatim. */
    private data class Target(val directHost: String?, val directPort: Int?, val relayUrl: String?)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler("Network"))
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var target: Target? = null

    @Volatile
    private var failures = 0

    private var loop: Job? = null

    /**
     * Arms the periodic re-announce with the endpoints just published (the
     * initial publish is done by the caller, so the first re-announce is one
     * [PERIOD_MS] away). Calling it again only re-targets the running loop.
     */
    @Synchronized
    fun start(directHost: String?, directPort: Int?, relayUrl: String?) {
        target = Target(directHost, directPort, relayUrl)
        failures = 0
        if (loop?.isActive == true) return
        loop = scope.launch {
            var waitMs = PERIOD_MS
            while (isActive) {
                withTimeoutOrNull(waitMs) { wake.receive() }
                waitMs = announceOnce()
            }
        }
    }

    /** Re-announces as soon as the loop wakes up; no-op before [start]. */
    fun announceNow() {
        wake.trySend(Unit)
    }

    /** Cancels the loop (coordinator stop / secure wipe); a later [start] arms it again. */
    @Synchronized
    fun stop() {
        target = null
        loop = null
        scope.coroutineContext.cancelChildren()
    }

    /** One re-announce attempt; returns how long to wait before the next one. */
    private suspend fun announceOnce(): Long {
        val current = target?.takeIf { selfIdentityCache.get() != null } ?: return PERIOD_MS
        val result = publishNetworkEndpointsUseCase(
            directHost = current.directHost,
            directPort = current.directPort,
            relayUrl = current.relayUrl,
        )
        return when (result) {
            is AppResult.Success -> {
                failures = 0
                AppLogger.info("Network", "re-announce endpoints OK")
                PERIOD_MS
            }
            is AppResult.Error -> {
                failures += 1
                val next = backoffMs(failures)
                AppLogger.info(
                    "Network",
                    "re-announce endpoints failed (${result.error.message}), next in ${next}ms",
                )
                next
            }
        }
    }

    private fun backoffMs(consecutiveFailures: Int): Long =
        (FAILURE_BACKOFF_MS shl minOf(consecutiveFailures - 1, MAX_BACKOFF_SHIFT)).coerceAtMost(PERIOD_MS)

    companion object {
        /** Half the record TTL: a single missed re-announce still leaves us resolvable. */
        const val PERIOD_MS = DhtDiscoveryProvider.DEFAULT_TTL_MS / 2

        /** First retry delay after a failed attempt; doubles up to [PERIOD_MS]. */
        const val FAILURE_BACKOFF_MS = 60_000L

        private const val MAX_BACKOFF_SHIFT = 8
    }
}
