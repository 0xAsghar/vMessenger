package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.ChatMessage
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.repository.FakeIdentityRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A parked copy of a timed message is held no longer than the message itself lives. */
class MailboxExpiryTest {
    private val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val peer = InboundFixtures.peer(0x0C)
    private val mailboxDao = FakeMailboxDao()
    private lateinit var service: MailboxService
    private var flagBefore = false

    @Before
    fun setUp() {
        flagBefore = P2PConfig.storeAndForwardEnabled
        P2PConfig.storeAndForwardEnabled = true
        val identities = FakeIdentityRepository(crypto)
        InboundFixtures.installIdentity(identities, 0x01)
        service = MailboxService(mailboxDao, identities, MailboxSeal(crypto))
    }

    @After
    fun tearDown() {
        P2PConfig.storeAndForwardEnabled = flagBefore
    }

    @Test
    fun `a timed message's copy expires with the message, not a day later`() = runTest {
        val deadline = System.currentTimeMillis() + TEN_MINUTES

        assertTrue(service.enqueueForRecipient(peer, envelope(expiresAt = deadline)))

        assertEquals(deadline, mailboxDao.blobs.single().expiresAtUnixMs)
    }

    @Test
    fun `an ordinary message keeps the mailbox's own limit`() = runTest {
        val before = System.currentTimeMillis()

        assertTrue(service.enqueueForRecipient(peer, envelope(expiresAt = 0L), ttlMs = ONE_DAY))

        val expires = mailboxDao.blobs.single().expiresAtUnixMs
        assertTrue(expires in before + ONE_DAY..System.currentTimeMillis() + ONE_DAY)
    }

    @Test
    fun `a message whose deadline outlasts the mailbox limit keeps the limit`() = runTest {
        val before = System.currentTimeMillis()

        service.enqueueForRecipient(peer, envelope(expiresAt = before + 7 * ONE_DAY), ttlMs = ONE_DAY)

        assertTrue(mailboxDao.blobs.single().expiresAtUnixMs <= System.currentTimeMillis() + ONE_DAY)
    }

    @Test
    fun `an already-expired message is not parked at all`() = runTest {
        assertFalse(service.enqueueForRecipient(peer, envelope(expiresAt = System.currentTimeMillis() - 1)))
        assertTrue(mailboxDao.blobs.isEmpty())
    }

    private fun envelope(expiresAt: Long): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("m-${System.nanoTime()}"))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setCounter(1)
        .setExpiresAtUnixMs(expiresAt)
        .setChat(ChatMessage.newBuilder().setText("hi"))
        .build()

    private companion object {
        const val TEN_MINUTES = 10 * 60_000L
        const val ONE_DAY = 24 * 60 * 60_000L
    }
}
