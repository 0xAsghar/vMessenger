package ir.vmessenger.data.call

import ir.vmessenger.core.audio.JitterBuffer
import ir.vmessenger.core.audio.JitterFrame
import ir.vmessenger.core.audio.OpusCodec
import ir.vmessenger.core.audio.VoiceAudio
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.network.transport.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Which end of the call this is: who opens paths, and who waits for them. */
internal enum class MediaRole {
    /** Opens the paths, and greets on each until the other end answers on one. */
    Caller,

    /** Takes the first path that proves itself, and answers on that one. */
    Callee,
}

/**
 * One call's audio, over whichever connection is carrying it.
 *
 * A call can have several connections at once — a direct socket and a relay circuit raced at the
 * start, or new ones opened after the one in use died — but its audio travels on one: the *bound*
 * path. Which one is settled without a message of its own. The caller greets on every path it opens;
 * the callee binds to the first path on which anything authenticates, closes the rest, and answers on
 * it; the caller binds to the path the answer came back on. So both ends always agree, and a path is
 * bound only once the far end has proved, with the call's key, that it is on the other end of it.
 *
 * A bound path is watched. A live call sends fifty frames a second, silence and mute included, so
 * [SILENCE_LIMIT_MS] without an authenticated one means the path is gone, whether or not its socket
 * has noticed. Losing the bound path reports [CallEvent.MediaLost]; the next path to bind reports
 * [CallEvent.MediaRestored]. The microphone and the speaker run only while a path is bound.
 *
 * Anyone can open a connection to a media port. One that has not authenticated a frame within
 * [PROOF_DEADLINE_MS] is closed, and only [MAX_CANDIDATES] are held at once, so a stranger cannot
 * keep a reconnecting caller out by filling the slots.
 */
internal class CallMediaSession(
    private val sealer: MediaSealer,
    private val devices: MediaDevices,
    private val role: MediaRole,
    private val events: suspend (CallEvent) -> Unit,
    private val scope: CoroutineScope,
) {
    /** Injectable clock so the silence watchdog is testable. */
    var clock: () -> Long = System::currentTimeMillis

    private val lock = Any()
    private val candidates = mutableSetOf<Connection>()
    private val _up = MutableStateFlow(false)

    /**
     * Serialises what the coordinator is told, and each report is re-checked against the state when
     * its turn comes: a loss and the binding that repaired it can race, and "lost" arriving after
     * "restored" would leave a working call counting down to a reconnect timeout.
     */
    private val reporting = Mutex()

    @Volatile
    private var bound: BoundPath? = null

    /** The most recent bound path's job, finished or not; the next path waits for it. */
    private var releasing: Job? = null

    @Volatile
    private var everBound = false

    @Volatile
    private var closed = false

    /** Whether a path is carrying the call right now. */
    val up: StateFlow<Boolean> = _up.asStateFlow()

    /** A connection that may carry the call. It is kept and read if wanted, closed if not. */
    fun offer(connection: Connection) {
        scope.launch { carry(connection) }
    }

    /**
     * Ends the call's audio: every path closed, the devices released, and the key wiped once the last
     * coroutine that could use it has finished. Not suspending: the coordinator calls it under a lock.
     */
    fun close() {
        synchronized(lock) {
            closed = true
            bound = null
            candidates.clear()
        }
        _up.value = false
        scope.coroutineContext[Job]?.invokeOnCompletion { sealer.wipe() }
        scope.cancel()
        if (everBound) devices.closeSession()
    }

    private suspend fun carry(connection: Connection) {
        if (!admit(connection)) {
            runCatching { connection.close() }
            return
        }
        val greeting = if (role == MediaRole.Caller) scope.launch { greet(connection) } else null
        val deadline = scope.launch {
            delay(PROOF_DEADLINE_MS)
            if (bound?.connection !== connection) {
                AppLogger.info(TAG, "a media connection proved nothing in ${PROOF_DEADLINE_MS}ms; closing it")
                runCatching { connection.close() }
            }
        }
        try {
            connection.read().collect { raw -> receive(connection, raw) }
        } finally {
            greeting?.cancel()
            deadline.cancel()
            withContext(NonCancellable) { drop(connection) }
        }
    }

    /** Room for it, and — for a caller already bound — a use for it: a late dial has none. */
    private fun admit(connection: Connection): Boolean = synchronized(lock) {
        val wanted = !closed &&
            candidates.size < MAX_CANDIDATES &&
            !(role == MediaRole.Caller && bound != null)
        if (wanted) candidates += connection
        wanted
    }

    /** Until some path is bound: an authenticated nothing, so the callee can tell this path reaches it. */
    private suspend fun greet(connection: Connection) {
        while (!_up.value) {
            connection.sendOrClose(sealer.seal())
            delay(GREETING_INTERVAL_MS)
        }
    }

    private suspend fun receive(connection: Connection, raw: ByteArray) {
        val frame = sealer.open(raw) ?: return
        val path = bind(connection) ?: return
        path.heardAt = clock()
        if (!frame.greeting) path.offer(frame)
    }

    /** The bound path for [connection], binding it first if it is not; null once the call is over. */
    private suspend fun bind(connection: Connection): BoundPath? {
        val current = bound
        if (current != null && current.connection === connection) return current
        val change = synchronized(lock) { rebind(connection) }
        if (change != null) announce(change)
        return change?.path
    }

    /**
     * Makes [connection] the bound path, if it should be. Runs under [lock]; null when it should not.
     *
     * Not when the call has ended, not when an earlier binding already let this connection go, and
     * not while the path in use is still being heard. The last is the one that matters: a caller
     * greets on every path until it binds, so for a moment a callee hears the same caller on two, and
     * moving to the second would strand the caller on the first. A path the caller has really left
     * goes quiet, and then a new one is welcome.
     */
    private fun rebind(connection: Connection): Rebinding? {
        val current = bound
        val stay = closed ||
            connection !in candidates ||
            (current != null && clock() - current.heardAt < SWITCH_AFTER_MS)
        if (stay) return null
        val first = !everBound
        if (first) devices.openSession()
        everBound = true
        bound?.job?.cancel()
        val displaced = candidates.filter { it !== connection }
        candidates.retainAll { it === connection }
        val path = BoundPath(connection, devices.codec(), clock())
        val after = releasing
        path.job = scope.launch { path.run(after) }
        releasing = path.job
        bound = path
        _up.value = true
        return Rebinding(path, displaced, first)
    }

    private suspend fun announce(change: Rebinding) {
        change.displaced.forEach { runCatching { it.close() } }
        AppLogger.info(TAG, "media path bound via ${change.path.connection.remote.transport.value}")
        reporting.withLock {
            if (bound === change.path) events(if (change.first) CallEvent.MediaUp else CallEvent.MediaRestored)
        }
    }

    private suspend fun drop(connection: Connection) {
        runCatching { connection.close() }
        val lost = synchronized(lock) {
            candidates.remove(connection)
            bound?.takeIf { it.connection === connection }?.also {
                bound = null
                _up.value = false
                it.job?.cancel()
            }
        }
        if (lost != null) {
            AppLogger.info(TAG, "media path lost")
            reporting.withLock { if (bound == null && !closed) events(CallEvent.MediaLost) }
        }
    }

    /**
     * The path audio travels on, and everything that runs on it: the microphone out, the speaker in,
     * and a watch on silence. The jitter buffer is shared by the reader and the player, hence the lock.
     */
    private inner class BoundPath(val connection: Connection, private val codec: OpusCodec, heardAt: Long) {
        @Volatile
        var heardAt: Long = heardAt

        @Volatile
        var job: Job? = null

        private val jitter = JitterBuffer()

        fun offer(frame: OpenedFrame) = synchronized(jitter) {
            jitter.offer(frame.sequence, frame.payload, frame.payload.size)
        }

        /**
         * Everything this path does, as one job. [after] is the path it replaced: the microphone and
         * the speaker are one each, so nothing here opens them until that path has let go of them.
         */
        suspend fun run(after: Job?) {
            after?.join()
            try {
                coroutineScope {
                    launch { transmit() }
                    launch { play() }
                    launch { watch() }
                }
            } finally {
                runCatching { codec.close() }
            }
        }

        private suspend fun transmit() {
            val packet = ByteArray(VoiceAudio.MAX_PACKET_BYTES)
            // At once, not once the microphone has warmed up: the far end binds on the first frame it
            // can authenticate, and a greeting is one.
            connection.sendOrClose(sealer.seal())
            devices.capture().collect { pcm ->
                val encoded = codec.encode(pcm, packet)
                if (encoded > 0) connection.sendOrClose(sealer.seal(packet, encoded))
            }
            // The capture ended by itself: no microphone, or it stopped mid-call. Not a path problem,
            // so not one a new path could fix — the call ends rather than going on one-way.
            AppLogger.warn(TAG, "the microphone stopped; ending the call's media")
            events(CallEvent.MediaFailed)
        }

        /**
         * Paced by the device, not by a timer: [MediaDevices.play] returns once the frame fits, so the
         * loop settles at real time on its own. A concealed frame is played too — silence keeps the
         * stream in step, where skipping it would shift everything after it earlier.
         */
        private suspend fun play() {
            val pcm = ShortArray(VoiceAudio.SAMPLES_PER_FRAME)
            devices.openPlayback()
            try {
                while (currentCoroutineContext().isActive) {
                    val samples = when (val next = synchronized(jitter) { jitter.poll() }) {
                        is JitterFrame.Packet -> codec.decode(next.payload, next.length, pcm)
                        JitterFrame.Conceal -> codec.decode(null, 0, pcm)
                    }
                    devices.play(pcm, samples)
                }
            } finally {
                devices.closePlayback()
            }
        }

        /** Closing the connection ends its reader, and the reader is what reports the loss. */
        private suspend fun watch() {
            while (clock() - heardAt < SILENCE_LIMIT_MS) delay(WATCH_INTERVAL_MS)
            AppLogger.warn(TAG, "nothing heard for ${SILENCE_LIMIT_MS}ms; dropping the media path")
            runCatching { connection.close() }
        }
    }

    /** What a binding changed: the new path, the connections it displaced, and whether it is the first. */
    private class Rebinding(val path: BoundPath, val displaced: List<Connection>, val first: Boolean)

    companion object {
        private const val TAG = "Call"

        /** Fifty frames a second flow on a live path; this long without one and it is not live. */
        const val SILENCE_LIMIT_MS = 5_000L

        /** How long a connection may sit unproven before it is taken for a stranger's. */
        const val PROOF_DEADLINE_MS = 10_000L

        const val MAX_CANDIDATES = 8

        /** How quiet the path in use must be before another may replace it. */
        const val SWITCH_AFTER_MS = 1_000L
        private const val GREETING_INTERVAL_MS = 250L
        private const val WATCH_INTERVAL_MS = 1_000L
    }
}

/** A path that cannot be written to is as gone as one that cannot be read; closing it says so. */
private suspend fun Connection.sendOrClose(frame: ByteArray) {
    write(frame).onFailure { runCatching { close() } }
}
