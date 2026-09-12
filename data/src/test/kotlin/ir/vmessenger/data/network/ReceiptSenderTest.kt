package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeIdentityRepository
import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.RatchetState
import ir.vmessenger.network.messaging.SymmetricRatchet
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiptSenderTest {
    private val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val peerA = InboundFixtures.peer(0x0A)

    private lateinit var contactDao: FakeContactDao
    private lateinit var messaging: FakeMessagingPort
    private lateinit var sender: ReceiptSender

    /** Records every frame written; nothing is ever read back. */
    private class RecordingConnection : Connection {
        val frames = mutableListOf<ByteArray>()
        private val _state = MutableStateFlow(ConnectionState.OPEN)
        override val remote = Endpoint(TransportIds.INTERNET, "fake://peer")
        override val state: StateFlow<ConnectionState> = _state

        override suspend fun write(frame: ByteArray): Result<Unit> {
            frames += frame
            return Result.success(Unit)
        }

        override fun read(): Flow<ByteArray> = emptyFlow()

        override suspend fun close() {
            _state.value = ConnectionState.CLOSED
        }
    }

    @Before
    fun setUp() {
        val identityRepository = FakeIdentityRepository(cryptoEngine)
        InboundFixtures.installIdentity(identityRepository, 0x01)
        contactDao = FakeContactDao().apply { contacts += InboundFixtures.contact("a", peerA) }
        messaging = FakeMessagingPort()
        sender = ReceiptSender(
            messaging = messaging,
            selfIdentityCache = SelfIdentityCache(identityRepository, cryptoEngine),
            contactDao = contactDao,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun session(connection: Connection) = ActiveSecureSession(
        peer = peerA,
        selfPublicKeyHash = ByteArray(32) { 1 },
        peerPublicKeyHash = ByteArray(32) { 2 },
        ratchetState = RatchetState(sendChainKey = ByteArray(32) { 3 }, recvChainKey = ByteArray(32) { 4 }),
        ratchet = SymmetricRatchet(cryptoEngine),
        connection = connection,
    )

    @Test
    fun prefersInboundSession() = runTest {
        val connection = RecordingConnection()
        messaging.existingSessionContacts += "a"

        sender.enqueueDelivered("a", "m1", 1_000L, session(connection))
        sender.start()

        assertEquals(1, connection.frames.size)
        assertTrue(messaging.sentOnExistingSession.isEmpty())
        assertTrue(messaging.sent.isEmpty())
    }

    @Test
    fun fallsBackToOutbound() = runTest {
        // Closed inbound session: the open outbound session is next in line.
        val closed = session(RecordingConnection()).also { it.close() }
        messaging.existingSessionContacts += "a"
        sender.enqueueDelivered("a", "m1", 1_000L, closed)
        sender.start()

        assertEquals(listOf("a"), messaging.sentOnExistingSession.map { it.first })
        assertTrue(messaging.sent.isEmpty())

        // No session anywhere: a bounded dial as the last resort.
        messaging.existingSessionContacts.clear()
        sender.enqueueDelivered("a", "m2", 2_000L, null)

        assertEquals(1, messaging.sentOnExistingSession.size)
        assertEquals(1, messaging.receiptsFor("m2").size)
        assertEquals(ReceiptType.RECEIPT_TYPE_DELIVERED, messaging.sent.single().second.receipt.type)
    }

    @Test
    fun batchesReadReceipts() = runTest {
        sender.enqueueRead("a", listOf("r1"), 1_000L)
        sender.enqueueRead("a", listOf("r2", "r3"), 2_000L)
        sender.enqueueRead("a", listOf("r4"), 3_000L)
        sender.enqueueDelivered("a", "d1", 4_000L, null)
        sender.start()

        assertEquals(2, messaging.sent.size)
        val read = messaging.sent.first().second.receipt
        assertEquals(ReceiptType.RECEIPT_TYPE_READ, read.type)
        val refs = listOf(read.refMessageId.toStringUtf8()) + read.refMessageIdsList.map { it.toStringUtf8() }
        assertEquals(listOf("r1", "r2", "r3", "r4"), refs)
        assertEquals(3_000L, read.atUnixMs)
        assertEquals(ReceiptType.RECEIPT_TYPE_DELIVERED, messaging.sent.last().second.receipt.type)
    }

    @Test
    fun sendFailureIsDroppedNotRetried() = runTest {
        messaging.sendError = AppError.Network("offline")
        sender.enqueueDelivered("a", "m1", 1_000L, null)
        sender.start()

        assertTrue(messaging.sent.isEmpty())
        // The queue keeps serving afterwards.
        messaging.sendError = null
        sender.enqueueDelivered("a", "m2", 2_000L, null)
        assertEquals(1, messaging.receiptsFor("m2").size)
    }
}
