package ir.vmessenger.data.network

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.network.messaging.IncomingEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fans authenticated inbound envelopes out to one worker per contact so a slow
 * handler (a large attachment, a stalled DB write, a contact request that
 * waits on a dial) never delays other contacts' messages. Every `stranger:*`
 * sender shares one small worker: they can only send contact requests, and
 * one queue bounds what unknown peers can make us buffer.
 *
 * Each worker is a bounded channel plus a coroutine under a supervisor: a
 * handler failure is logged and the worker moves on; the channel filling up
 * suspends the session read loop for *that* contact only (transport
 * backpressure). Workers idle out after [idleTimeoutMs] and are recreated on
 * demand. A worker that dies for any other reason (an `Error` escaping a
 * handler) unregisters itself and re-routes what it still held, so its queue
 * can never turn into a dead end that stalls the read loop for good.
 */
class IncomingWorkerRouter(
    dispatcher: CoroutineDispatcher,
    private val handler: suspend (IncomingEnvelope) -> Unit,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    private class Worker(val channel: Channel<IncomingEnvelope>)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + loggingExceptionHandler(TAG))
    private val workers = HashMap<String, Worker>()
    private val workersLock = Mutex()

    /** Number of live workers (tests/diagnostics). */
    suspend fun workerCount(): Int = workersLock.withLock { workers.size }

    /**
     * Hands [incoming] to its contact's worker; suspends while that worker's
     * queue is full. A worker that retired between lookup and send is simply
     * replaced and the send retried. After [stop] the envelope is dropped.
     */
    suspend fun route(incoming: IncomingEnvelope) {
        val key = workerKey(incoming.contactId)
        while (scope.isActive) {
            val worker = workersLock.withLock { workers.getOrPut(key) { spawn(key) } }
            try {
                worker.channel.send(incoming)
                return
            } catch (_: ClosedSendChannelException) {
                // Retired concurrently; loop to create a fresh worker.
            }
        }
        AppLogger.warn(TAG, "router stopped; inbound envelope dropped contact=${incoming.contactId}")
    }

    /** Cancels every worker; envelopes still queued are dropped (wipe / shutdown). */
    fun stop() {
        scope.cancel()
    }

    private fun spawn(key: String): Worker {
        val capacity = if (key == STRANGER_KEY) STRANGER_CAPACITY else CONTACT_CAPACITY
        val channel = Channel<IncomingEnvelope>(capacity)
        scope.launch { serve(key, channel) }
        return Worker(channel)
    }

    private suspend fun serve(key: String, channel: Channel<IncomingEnvelope>) {
        try {
            while (true) {
                val next = withTimeoutOrNull(idleTimeoutMs) { channel.receive() }
                if (next != null) {
                    handle(next)
                } else if (retire(key, channel)) {
                    break
                }
            }
        } finally {
            withContext(NonCancellable) { abandon(key, channel) }
        }
    }

    /** Removes and closes the worker unless something arrived meanwhile; true when retired. */
    private suspend fun retire(key: String, channel: Channel<IncomingEnvelope>): Boolean =
        workersLock.withLock {
            if (channel.isEmpty && workers[key]?.channel === channel) {
                workers.remove(key)
                channel.close()
                true
            } else {
                false
            }
        }

    /**
     * Runs however the worker ended: unregisters it (unless a newer worker
     * already took the key), closes its queue and hands anything still queued
     * to a fresh worker — unless the router itself is stopping, in which case
     * the leftovers are dropped as documented on [stop].
     */
    private suspend fun abandon(key: String, channel: Channel<IncomingEnvelope>) {
        workersLock.withLock {
            if (workers[key]?.channel === channel) workers.remove(key)
        }
        channel.close()
        while (scope.isActive) {
            val leftover = channel.tryReceive().getOrNull() ?: break
            route(leftover)
        }
    }

    @Suppress("TooGenericExceptionCaught") // one bad envelope must not take the worker down with it
    private suspend fun handle(incoming: IncomingEnvelope) {
        try {
            handler(incoming)
        } catch (e: CancellationException) {
            // A timeout inside the handler is its own failure; only the
            // worker's own cancellation (stop) may end the loop.
            currentCoroutineContext().ensureActive()
            AppLogger.warn(TAG, "inbound handler timed out contact=${incoming.contactId}: ${e.message}")
        } catch (e: Exception) {
            AppLogger.warn(TAG, "inbound handler failed contact=${incoming.contactId}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Inbound"
        const val STRANGER_PREFIX = "stranger:"
        const val CONTACT_CAPACITY = 64
        const val STRANGER_CAPACITY = 16
        const val IDLE_TIMEOUT_MS = 5 * 60_000L
        private const val STRANGER_KEY = "stranger:*"

        fun workerKey(contactId: String): String =
            if (contactId.startsWith(STRANGER_PREFIX)) STRANGER_KEY else contactId
    }
}
