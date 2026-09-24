package ir.vmessenger.data.call

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.audio.OpusCodec
import ir.vmessenger.core.audio.VoiceAudio
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a call's audio chooses, loses and replaces the connection it travels on. */
class CallMediaSessionTest {
    private val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val key = ByteArray(32) { it.toByte() }
    private val open = mutableListOf<CallMediaSession>()

    private val callerDevices = FakeDevices()
    private val calleeDevices = FakeDevices()
    private val callerEvents = mutableListOf<CallEvent>()
    private val calleeEvents = mutableListOf<CallEvent>()

    @Test
    fun `both ends settle on the path the callee answered on, and let the other go`() = sessionTest {
        val (caller, callee) = sessions()
        val (relayOut, relayIn) = pipe("relay")
        val (directOut, directIn) = pipe("direct")
        callee.offer(relayIn)
        callee.offer(directIn)

        caller.offer(relayOut)
        runCurrent()
        // Too late: the caller is already bound, so a dial that lands now is closed unused.
        caller.offer(directOut)
        passes(1_000)

        assertEquals(listOf(CallEvent.MediaUp), callerEvents)
        assertEquals(listOf(CallEvent.MediaUp), calleeEvents)
        assertTrue(caller.up.value && callee.up.value)
        assertEquals(ConnectionState.CLOSED, directIn.state.value)
        assertTrue("audio plays at both ends", callerDevices.played > 0 && calleeDevices.played > 0)
    }

    @Test
    fun `greetings on two paths at once cannot pull the callee off the path it already chose`() = sessionTest {
        val (caller, callee) = sessions()
        val (one, oneIn) = pipe("one")
        val (two, twoIn) = pipe("two")
        callee.offer(oneIn)
        callee.offer(twoIn)
        // The caller greets on both before either answer can reach it — the race a device showed.
        caller.offer(one)
        caller.offer(two)
        passes(2_000)

        assertEquals(listOf(CallEvent.MediaUp), calleeEvents)
        assertEquals(listOf(CallEvent.MediaUp), callerEvents)
        val open = listOf(oneIn, twoIn).filter { it.state.value == ConnectionState.OPEN }
        assertEquals("exactly one path survives, the same one at both ends", 1, open.size)
        assertTrue(callerDevices.played > 0 && calleeDevices.played > 0)
    }

    @Test
    fun `a lost path is replaced, and the new one continues the sequence rather than repeating it`() = sessionTest {
        val (caller, callee) = sessions()
        val (first, firstIn) = pipe("first")
        callee.offer(firstIn)
        caller.offer(first)
        passes(1_000)

        first.close()
        runCurrent()
        assertEquals(CallEvent.MediaLost, callerEvents.last())
        assertEquals(CallEvent.MediaLost, calleeEvents.last())

        val (second, secondIn) = pipe("second")
        callee.offer(secondIn)
        caller.offer(second)
        passes(1_000)

        assertEquals(listOf(CallEvent.MediaUp, CallEvent.MediaLost, CallEvent.MediaRestored), callerEvents)
        assertEquals(listOf(CallEvent.MediaUp, CallEvent.MediaLost, CallEvent.MediaRestored), calleeEvents)
        val before = first.sequences()
        val after = second.sequences()
        assertTrue(before.isNotEmpty() && after.isNotEmpty())
        assertTrue("one counter per call: a nonce is never reused", after.min() > before.max())
        assertEquals((before + after).size, (before + after).toSet().size)
    }

    @Test
    fun `a path that goes quiet is dropped even though its socket never noticed`() = sessionTest {
        val (caller, callee) = sessions()
        val (out, inbound) = pipe("stalled")
        callee.offer(inbound)
        caller.offer(out)
        passes(1_000)

        out.dropping = true
        inbound.dropping = true
        passes(CallMediaSession.SILENCE_LIMIT_MS + 2_000)

        assertEquals(CallEvent.MediaLost, callerEvents.last())
        assertEquals(CallEvent.MediaLost, calleeEvents.last())
        assertFalse(caller.up.value || callee.up.value)
    }

    @Test
    fun `a stranger on the port proves nothing, binds nothing, and is shown out`() = sessionTest {
        val (caller, callee) = sessions()
        val (stranger, strangerIn) = pipe("stranger")
        callee.offer(strangerIn)
        stranger.write(ByteArray(64) { 7 })
        runCurrent()
        assertFalse(callee.up.value)

        passes(CallMediaSession.PROOF_DEADLINE_MS + 1_000)
        assertEquals(ConnectionState.CLOSED, strangerIn.state.value)

        val (real, realIn) = pipe("real")
        callee.offer(realIn)
        caller.offer(real)
        passes(1_000)
        assertTrue(callee.up.value)
    }

    @Test
    fun `a microphone that will not open ends the media rather than the call going one-way`() = sessionTest {
        calleeDevices.microphone = false
        val (caller, callee) = sessions()
        val (out, inbound) = pipe("path")
        callee.offer(inbound)
        caller.offer(out)
        passes(1_000)

        assertTrue(CallEvent.MediaFailed in calleeEvents)
    }

    @Test
    fun `closing wipes the key once nothing can use it, and puts the audio mode back`() = sessionTest {
        val theirKey = key.copyOf()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val sealer = MediaSealer(crypto, theirKey, MediaDirection.CallerToCallee)
        val caller = CallMediaSession(sealer, callerDevices, MediaRole.Caller, { callerEvents += it }, scope)
        open += caller
        val callee = session(MediaRole.Callee)
        val (out, inbound) = pipe("path")
        callee.offer(inbound)
        caller.offer(out)
        passes(1_000)
        assertTrue(callerDevices.sessionOpen)

        caller.close()
        runCurrent()

        assertTrue(theirKey.all { it == 0.toByte() })
        assertFalse(callerDevices.sessionOpen)
    }

    /**
     * Closes every session before the test ends. Their microphone and speaker loops never go idle,
     * and runTest runs whatever is left on the scheduler until it is, so an open one never ends.
     */
    private fun sessionTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            open.forEach { it.close() }
            open.clear()
        }
    }

    private fun TestScope.sessions(): Pair<CallMediaSession, CallMediaSession> =
        session(MediaRole.Caller) to session(MediaRole.Callee)

    private fun TestScope.session(role: MediaRole): CallMediaSession {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val caller = role == MediaRole.Caller
        val direction = if (caller) MediaDirection.CallerToCallee else MediaDirection.CalleeToCaller
        val events = if (caller) callerEvents else calleeEvents
        val session = CallMediaSession(
            MediaSealer(crypto, key.copyOf(), direction),
            if (caller) callerDevices else calleeDevices,
            role,
            { events += it },
            scope,
        )
        session.clock = { testScheduler.currentTime }
        open += session
        return session
    }

    private fun TestScope.passes(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    /** Two ends of one in-memory connection, (caller's, callee's). */
    private fun pipe(label: String): Pair<Pipe, Pipe> {
        val toCallee = Channel<ByteArray>(Channel.UNLIMITED)
        val toCaller = Channel<ByteArray>(Channel.UNLIMITED)
        return Pipe(toCaller, toCallee, label) to Pipe(toCallee, toCaller, label)
    }

    /** One end of an in-memory connection. Closing either end ends both, as a socket would. */
    private class Pipe(
        private val inbound: Channel<ByteArray>,
        private val outbound: Channel<ByteArray>,
        label: String,
    ) : Connection {
        /** Swallows what is written, as a path that has died without its socket noticing. */
        var dropping = false
        private val written = mutableListOf<ByteArray>()
        private val _state = MutableStateFlow(ConnectionState.OPEN)
        override val remote = Endpoint(TransportIds.INTERNET, label)
        override val state: StateFlow<ConnectionState> = _state

        override suspend fun write(frame: ByteArray): Result<Unit> = runCatching {
            written += frame
            if (!dropping) outbound.send(frame)
        }

        override fun read(): Flow<ByteArray> = inbound.receiveAsFlow()

        override suspend fun close() {
            _state.value = ConnectionState.CLOSED
            inbound.close()
            outbound.close()
        }

        fun sequences(): List<Int> = written.map { CallMediaFrames.sequenceOf(it) }
    }

    /** A microphone that hands out frames on time, a speaker that takes them on time, and no sound. */
    private class FakeDevices : MediaDevices {
        var microphone = true
        var sessionOpen = false
        var played = 0

        override fun capture(): Flow<ShortArray> = flow {
            while (microphone) {
                emit(ShortArray(VoiceAudio.SAMPLES_PER_FRAME))
                delay(VoiceAudio.FRAME_MS.toLong())
            }
        }

        override fun codec(): OpusCodec = object : OpusCodec {
            override fun encode(pcm: ShortArray, packet: ByteArray): Int {
                packet.fill(1, 0, PACKET_BYTES)
                return PACKET_BYTES
            }

            override fun decode(packet: ByteArray?, length: Int, pcm: ShortArray): Int = VoiceAudio.SAMPLES_PER_FRAME

            override fun close() = Unit
        }

        override fun openSession() {
            sessionOpen = true
        }

        override fun closeSession() {
            sessionOpen = false
        }

        override fun openPlayback() = Unit

        override suspend fun play(pcm: ShortArray, samples: Int) {
            played++
            delay(VoiceAudio.FRAME_MS.toLong())
        }

        override fun closePlayback() = Unit

        private companion object {
            const val PACKET_BYTES = 3
        }
    }
}
