package ir.vmessenger.data.call

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun TestScope.coordinator(): CallCoordinator {
        val identities = FakeIdentityRepository(crypto)
        InboundFixtures.installIdentity(identities, 0x01)
        return CallCoordinator(
            contactDao = contactDao,
            selfIdentityCache = SelfIdentityCache(identities, crypto),
            messaging = messaging,
            crypto = crypto,
            media = SilentMedia,
            activityLogger = testActivityLogger(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun TestScope.passes(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun CallCoordinator.callId(): String = session.value!!.callId

    private fun lastSignal(): CallSignal = messaging.sent.last().second.callSignal

    /** A signal from the peer, carrying the ephemeral key and address an invite or accept would. */
    private fun signal(callId: String, type: CallSignalType): MessageEnvelope = MessageEnvelope.newBuilder()
        .setCallSignal(
            CallSignal.newBuilder()
                .setCallId(ByteString.copyFromUtf8(callId))
                .setType(type)
                .setMediaEphemeralPub(ByteString.copyFrom(crypto.generateX25519KeyPair().publicKey))
                .addMediaEndpoints(CallEndpoint.newBuilder().setAddress(PEER_ADDRESS).setRelay(false)),
        )
        .build()

    /** A media path that connects to nothing and carries nothing: only the signalling is under test. */
    private object SilentMedia : CallMediaPort {
        override suspend fun accept(
            key: ByteArray,
            outgoing: Boolean,
            onEvent: suspend (CallEvent) -> Unit,
        ): List<String> = listOf(PEER_ADDRESS)

        override fun connect(
            addresses: List<String>,
            key: ByteArray,
            outgoing: Boolean,
            onEvent: suspend (CallEvent) -> Unit,
        ) = Unit

        override fun setMuted(muted: Boolean) = Unit

        override fun setSpeaker(on: Boolean) = Unit

        override fun stop() = Unit
    }

    private companion object {
        const val CONTACT = "bob"
        const val PEER_ADDRESS = "10.0.0.2:48557"
    }
}
