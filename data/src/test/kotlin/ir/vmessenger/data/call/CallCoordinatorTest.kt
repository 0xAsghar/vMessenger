package ir.vmessenger.data.call

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.CallEndpoint
import ir.vmessenger.core.proto.app.v1.CallRejectReason
import ir.vmessenger.core.proto.app.v1.CallSignal
import ir.vmessenger.core.proto.app.v1.CallSignalType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.activity.testActivityLogger
import ir.vmessenger.data.network.FakeMessagingPort
import ir.vmessenger.data.network.InboundFixtures
import ir.vmessenger.data.network.SelfIdentityCache
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeIdentityRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCoordinatorTest {
    private val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val peer = InboundFixtures.peer(0x0A)
    private val contactDao = FakeContactDao().apply { contacts += InboundFixtures.contact(CONTACT, peer) }
    private val messaging = FakeMessagingPort()

    @Test
    fun `an unanswered call is cancelled after a minute`() = runTest {
        val calls = coordinator()
        calls.dial(CONTACT)

        passes(59_000)
        assertEquals(CallState.OutgoingRinging, calls.session.value?.state)
        passes(2_000)

        assertNull(calls.session.value)
        assertEquals(CallSignalType.CALL_SIGNAL_TYPE_CANCEL, lastSignal().type)
        assertEquals(CallRejectReason.CALL_REJECT_REASON_TIMEOUT, lastSignal().rejectReason)
    }

    @Test
    fun `an invite that cannot be delivered ends the call at once, and says why`() = runTest {
        val calls = coordinator()
        val ends = endsOf(calls)
        messaging.sendError = AppError.Network("peer not listening")

        calls.dial(CONTACT)

        assertNull(calls.session.value)
        assertEquals(listOf(CallEndReason.Unreachable), ends.map { it.reason })
    }

    @Test
    fun `the caller hears why a ringing call ended`() = runTest {
        val calls = coordinator()
        val ends = endsOf(calls)

        calls.dial(CONTACT)
        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_REJECT))
        calls.dial(CONTACT)
        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_BUSY))
        calls.dial(CONTACT)
        passes(61_000)

        assertEquals(
            listOf(CallEndReason.Declined, CallEndReason.Busy, CallEndReason.NoAnswer),
            ends.map { it.reason },
        )
    }

    @Test
    fun `a hang-up in progress is no news`() = runTest {
        val calls = coordinator()
        val ends = endsOf(calls)
        calls.dial(CONTACT)
        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_ACCEPT))
        calls.onMediaEvent(CallEvent.MediaUp)

        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_HANGUP))

        assertNull(calls.session.value)
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `an answered call that never carries audio is hung up`() = runTest {
        val calls = coordinator()
        calls.dial(CONTACT)
        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_ACCEPT))
        assertEquals(CallState.Connecting, calls.session.value?.state)

        passes(31_000)

        assertNull(calls.session.value)
        assertEquals(CallSignalType.CALL_SIGNAL_TYPE_HANGUP, lastSignal().type)
    }

    @Test
    fun `a call in progress has no deadline`() = runTest {
        val calls = coordinator()
        calls.dial(CONTACT)
        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_ACCEPT))
        calls.onMediaEvent(CallEvent.MediaUp)

        passes(10 * 60_000)

        assertEquals(CallState.Active, calls.session.value?.state)
    }

    @Test
    fun `a live call that loses its path reconnects instead of ending`() = runTest {
        val calls = coordinator()
        val ends = endsOf(calls)
        answeredAndLive(calls)

        calls.onMediaEvent(CallEvent.MediaLost)
        assertEquals(CallState.Reconnecting, calls.session.value?.state)
        passes(20_000)
        calls.onMediaEvent(CallEvent.MediaRestored)

        assertEquals(CallState.Active, calls.session.value?.state)
        assertTrue(ends.isEmpty())
        // Not hung up: the only signals sent are the invite, and nothing after the answer.
        assertEquals(CallSignalType.CALL_SIGNAL_TYPE_INVITE, lastSignal().type)
    }

    @Test
    fun `the call's clock keeps running through a reconnect`() = runTest {
        val calls = coordinator()
        answeredAndLive(calls)
        val connectedAt = calls.session.value?.connectedAtMs

        calls.onMediaEvent(CallEvent.MediaLost)
        calls.onMediaEvent(CallEvent.MediaRestored)

        assertEquals(connectedAt, calls.session.value?.connectedAtMs)
    }

    @Test
    fun `a path that does not come back in time ends the call, and says why`() = runTest {
        val calls = coordinator()
        val ends = endsOf(calls)
        answeredAndLive(calls)

        calls.onMediaEvent(CallEvent.MediaLost)
        passes(31_000)

        assertNull(calls.session.value)
        assertEquals(CallSignalType.CALL_SIGNAL_TYPE_HANGUP, lastSignal().type)
        assertEquals(listOf(CallEndReason.Failed), ends.map { it.reason })
    }

    @Test
    fun `a media failure no new path could fix ends the call at once`() = runTest {
        val calls = coordinator()
        val ends = endsOf(calls)
        answeredAndLive(calls)

        calls.onMediaEvent(CallEvent.MediaFailed)

        assertNull(calls.session.value)
        assertEquals(CallSignalType.CALL_SIGNAL_TYPE_HANGUP, lastSignal().type)
        assertEquals(listOf(CallEndReason.Failed), ends.map { it.reason })
    }

    @Test
    fun `the caller dials the relay the callee advertised, naming the callee`() = runTest {
        val media = RecordingMedia()
        val calls = coordinator(media)
        calls.dial(CONTACT)

        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_ACCEPT, withRelay = true))

        assertEquals(ADVERTISED, media.dialled)
        assertTrue(media.dialledPeer.contentEquals(peer.identityHash))
    }

    @Test
    fun `an answer advertises the relay as a relay`() = runTest {
        val calls = coordinator(RecordingMedia())
        calls.handleSignal(CONTACT, signal("call-1", CallSignalType.CALL_SIGNAL_TYPE_INVITE))

        calls.accept()

        val advertised = lastSignal().mediaEndpointsList.map { MediaEndpoint(it.address, it.relay) }
        assertEquals(ADVERTISED, advertised)
    }

    @Test
    fun `a ringing call whose caller went away stops ringing`() = runTest {
        val calls = coordinator()
        calls.handleSignal(CONTACT, signal("call-1", CallSignalType.CALL_SIGNAL_TYPE_INVITE))
        assertEquals(CallState.IncomingRinging, calls.session.value?.state)

        passes(76_000)

        assertNull(calls.session.value)
        assertEquals(CallSignalType.CALL_SIGNAL_TYPE_REJECT, lastSignal().type)
    }

    @Test
    fun `a deadline left over from an earlier call ends nothing`() = runTest {
        val calls = coordinator()
        calls.dial(CONTACT)
        passes(40_000)
        calls.hangUp()
        calls.dial(CONTACT)

        // The first call's minute is up; the second's is not.
        passes(30_000)

        assertEquals(CallState.OutgoingRinging, calls.session.value?.state)
    }

    private fun TestScope.coordinator(media: CallMediaPort = SilentMedia): CallCoordinator {
        val identities = FakeIdentityRepository(crypto)
        InboundFixtures.installIdentity(identities, 0x01)
        return CallCoordinator(
            contactDao = contactDao,
            selfIdentityCache = SelfIdentityCache(identities, crypto),
            messaging = messaging,
            crypto = crypto,
            media = media,
            activityLogger = testActivityLogger(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    /** Dialled, answered and carrying audio. */
    private suspend fun answeredAndLive(calls: CallCoordinator) {
        calls.dial(CONTACT)
        calls.handleSignal(CONTACT, signal(calls.callId(), CallSignalType.CALL_SIGNAL_TYPE_ACCEPT))
        calls.onMediaEvent(CallEvent.MediaUp)
        assertEquals(CallState.Active, calls.session.value?.state)
    }

    private fun TestScope.endsOf(calls: CallCoordinator): List<CallEnd> {
        val ends = mutableListOf<CallEnd>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { calls.ended.toList(ends) }
        return ends
    }

    private fun TestScope.passes(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun CallCoordinator.callId(): String = session.value!!.callId

    private fun lastSignal(): CallSignal = messaging.sent.last().second.callSignal

    /** A signal from the peer, carrying the ephemeral key and address(es) an invite or accept would. */
    private fun signal(callId: String, type: CallSignalType, withRelay: Boolean = false): MessageEnvelope {
        val signal = CallSignal.newBuilder()
            .setCallId(ByteString.copyFromUtf8(callId))
            .setType(type)
            .setMediaEphemeralPub(ByteString.copyFrom(crypto.generateX25519KeyPair().publicKey))
            .addMediaEndpoints(CallEndpoint.newBuilder().setAddress(PEER_ADDRESS).setRelay(false))
        if (withRelay) signal.addMediaEndpoints(CallEndpoint.newBuilder().setAddress(RELAY_URL).setRelay(true))
        return MessageEnvelope.newBuilder().setCallSignal(signal).build()
    }

    /** A media path that connects to nothing and carries nothing: only the signalling is under test. */
    private object SilentMedia : CallMediaPort {
        override suspend fun accept(key: ByteArray, onEvent: suspend (CallEvent) -> Unit): List<MediaEndpoint> =
            listOf(MediaEndpoint(PEER_ADDRESS, relay = false))

        override fun connect(
            endpoints: List<MediaEndpoint>,
            peerIdentityHash: ByteArray,
            key: ByteArray,
            onEvent: suspend (CallEvent) -> Unit,
        ) = Unit

        override fun setMuted(muted: Boolean) = Unit

        override fun setSpeaker(on: Boolean) = Unit

        override fun stop() = Unit
    }

    /** Silent too, but advertises a relay and remembers what it was asked to dial. */
    private class RecordingMedia : CallMediaPort by SilentMedia {
        var dialled: List<MediaEndpoint> = emptyList()
        var dialledPeer: ByteArray = ByteArray(0)

        override suspend fun accept(key: ByteArray, onEvent: suspend (CallEvent) -> Unit): List<MediaEndpoint> =
            ADVERTISED

        override fun connect(
            endpoints: List<MediaEndpoint>,
            peerIdentityHash: ByteArray,
            key: ByteArray,
            onEvent: suspend (CallEvent) -> Unit,
        ) {
            dialled = endpoints
            dialledPeer = peerIdentityHash
        }
    }

    private companion object {
        const val CONTACT = "bob"
        const val PEER_ADDRESS = "10.0.0.2:48557"
        const val RELAY_URL = "wss://relay.example/relay"
        val ADVERTISED = listOf(MediaEndpoint(PEER_ADDRESS, relay = false), MediaEndpoint(RELAY_URL, relay = true))
    }
}
