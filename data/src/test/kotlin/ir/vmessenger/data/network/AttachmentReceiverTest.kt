package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.proto.app.v1.AttachmentChunk
import ir.vmessenger.core.proto.app.v1.AttachmentInfo
import ir.vmessenger.core.proto.app.v1.AttachmentKind
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.attachment.AttachmentTransferTracker
import ir.vmessenger.data.repository.FakeConversationDao
import ir.vmessenger.data.repository.FakeMessageDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentReceiverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: FakeAttachmentIncomingStore
    private lateinit var harness: InboundHarness
    private lateinit var conversationDao: FakeConversationDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var tracker: AttachmentTransferTracker
    private val dispatcher = StandardTestDispatcher()
    private lateinit var receiver: AttachmentReceiver
    private var now = 1_000_000L

    private val payload = Random(5).nextBytes(2 * AttachmentSender.CHUNK_BYTES + 321)
    private val chunkCount = 3

    @Before
    fun setUp() {
        store = FakeAttachmentIncomingStore(folder.root)
        harness = InboundHarness()
        conversationDao = harness.conversationDao
        messageDao = harness.messageDao
        tracker = AttachmentTransferTracker()
        receiver = AttachmentReceiver(
            store = store,
            conversationDao = conversationDao,
            conversationResolver = harness.conversationResolver,
            messageDao = messageDao,
            tracker = tracker,
            ioDispatcher = dispatcher,
        )
        receiver.clock = { now }
        // The resolver only creates a 1:1 conversation for a known contact, so the
        // transfers below have somewhere to land.
        harness.contactDao.contacts += InboundFixtures.contact("a", InboundFixtures.peer(0x0A))
        harness.contactDao.contacts += InboundFixtures.contact("b", InboundFixtures.peer(0x0B))
    }

    @Test
    fun outOfOrderAssembles() = runTest(dispatcher) {
        assertFalse(receiver.handleInfo("a", header("m1")))

        assertNull(receiver.handleChunk("a", chunk("m1", 2)))
        assertNull(receiver.handleChunk("a", chunk("m1", 0)))
        val done = receiver.handleChunk("a", chunk("m1", 1))

        assertNotNull(done)
        assertEquals("m1", done!!.messageId)
        val stored = messageDao.getById("m1")
        assertNotNull(stored)
        assertEquals(MessageDirection.INCOMING, stored!!.direction)
        assertTrue(stored.attachmentEncrypted)
        assertArrayEquals(sha256(payload), stored.attachmentSha256)
        assertEquals(payload.size.toLong(), stored.attachmentSizeBytes)
        assertArrayEquals(payload, store.imported.single().readBytes())
        assertEquals(1, conversationDao.getByContactId("a")!!.unreadCount)
        assertEquals(0, receiver.pendingCount())
        assertTrue(store.stagingDir.listFiles().isNullOrEmpty())
        assertNull(tracker.transfers.value["m1"])
    }

    @Test
    fun aTimedFileLandsWithTheSendersDeadline() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1", expiresAt = now + 60_000))

        for (index in 0 until chunkCount) receiver.handleChunk("a", chunk("m1", index))

        assertEquals(now + 60_000, messageDao.getById("m1")!!.expiresAtUnixMs)
    }

    @Test
    fun aFileAlreadyPastItsDeadlineIsAcknowledgedAndNeverStaged() = runTest(dispatcher) {
        assertTrue(receiver.handleInfo("a", header("m1", expiresAt = now - 1)))

        assertEquals(0, receiver.pendingCount())
        assertNull(receiver.handleChunk("a", chunk("m1", 0)))
        assertTrue(store.stagingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun aDeadlineThatPassesMidTransferDiscardsTheFile() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1", expiresAt = now + 1_000))
        receiver.handleChunk("a", chunk("m1", 0))
        receiver.handleChunk("a", chunk("m1", 1))

        now += 2_000
        val done = receiver.handleChunk("a", chunk("m1", 2))

        assertNull(done)
        assertNull(messageDao.getById("m1"))
        assertTrue(store.imported.isEmpty())
        assertTrue(store.stagingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun duplicateIgnored() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1"))

        assertNull(receiver.handleChunk("a", chunk("m1", 0)))
        assertNull(receiver.handleChunk("a", chunk("m1", 0)))
        assertEquals(AttachmentSender.CHUNK_BYTES.toLong(), tracker.transfers.value["m1"]!!.bytesDone)
        assertNull(receiver.handleChunk("a", chunk("m1", 1)))
        val done = receiver.handleChunk("a", chunk("m1", 2))

        assertNotNull(done)
        assertArrayEquals(payload, store.imported.single().readBytes())
    }

    @Test
    fun digestMismatchDiscards() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1", sha256 = ByteArray(32) { 0x42 }))

        for (index in 0 until chunkCount) {
            assertNull(receiver.handleChunk("a", chunk("m1", index)))
        }

        assertNull(messageDao.getById("m1"))
        assertTrue(store.imported.isEmpty())
        assertEquals(0, receiver.pendingCount())
        assertTrue(store.stagingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun headerResetsPartial() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1"))
        receiver.handleChunk("a", chunk("m1", 0))

        assertFalse(receiver.handleInfo("a", header("m1")))

        assertEquals(1, receiver.pendingCount())
        assertEquals(0L, tracker.transfers.value["m1"]!!.bytesDone)
        // Chunk 0 must be re-sent: the remaining two alone do not complete the transfer.
        assertNull(receiver.handleChunk("a", chunk("m1", 1)))
        assertNull(receiver.handleChunk("a", chunk("m1", 2)))
        assertNotNull(receiver.handleChunk("a", chunk("m1", 0)))
        assertArrayEquals(payload, store.imported.single().readBytes())
        assertTrue(store.stagingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun timerPrunesStale() = runTest(dispatcher) {
        receiver.start()
        receiver.handleInfo("a", header("m1"))
        receiver.handleChunk("a", chunk("m1", 0))
        assertEquals(1, receiver.pendingCount())

        now += AttachmentReceiver.STALE_TRANSFER_MS + 1
        advanceTimeBy(AttachmentReceiver.PRUNE_INTERVAL_MS + 1)

        assertEquals(0, receiver.pendingCount())
        assertTrue(store.stagingDir.listFiles().isNullOrEmpty())
        assertNull(tracker.transfers.value["m1"])
        assertNull(receiver.handleChunk("a", chunk("m1", 1)))
        receiver.stop()
    }

    /**
     * A process killed mid-receive leaves sealed chunk files behind: the receiver's
     * bookkeeping was in memory and the per-transfer key died with it, so nothing
     * can ever finish or read them, and no other path deletes them.
     */
    @Test
    fun startSweepsStagingLeftByAKilledProcess() = runTest(dispatcher) {
        val orphan = File(store.stagingDir, "left-behind.part").apply { writeBytes(ByteArray(8)) }

        receiver.start()
        runCurrent()
        receiver.stop()

        assertFalse(orphan.exists())
    }

    @Test
    fun startLeavesATransferInFlightAlone() = runTest(dispatcher) {
        assertFalse(receiver.handleInfo("a", header("m1")))
        assertNull(receiver.handleChunk("a", chunk("m1", 0)))
        val staged = store.stagingDir.listFiles()!!.single()

        receiver.start()
        runCurrent()
        receiver.stop()

        assertTrue(staged.exists())
        assertEquals(1, receiver.pendingCount())
    }

    @Test
    fun perContactCap() = runTest(dispatcher) {
        assertFalse(receiver.handleInfo("a", header("m1")))
        assertFalse(receiver.handleInfo("a", header("m2")))
        assertFalse(receiver.handleInfo("a", header("m3")))

        assertEquals(AttachmentReceiver.MAX_PER_CONTACT, receiver.pendingCount())
        assertNull(receiver.handleChunk("a", chunk("m3", 0)))
        // Another contact is not affected by a's cap.
        assertFalse(receiver.handleInfo("b", header("m4")))
        assertEquals(AttachmentReceiver.MAX_PER_CONTACT + 1, receiver.pendingCount())
    }

    @Test
    fun chunkFromOtherContactIgnored() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1"))

        assertNull(receiver.handleChunk("b", chunk("m1", 0)))

        assertEquals(0L, tracker.transfers.value["m1"]!!.bytesDone)
    }

    @Test
    fun wrongSizedChunkDropsTransfer() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1"))

        assertNull(receiver.handleChunk("a", chunk("m1", 0, data = ByteArray(10))))

        assertEquals(0, receiver.pendingCount())
    }

    @Test
    fun alreadyDeliveredHeaderAsksForReceipt() = runTest(dispatcher) {
        receiver.handleInfo("a", header("m1"))
        for (index in 0 until chunkCount) receiver.handleChunk("a", chunk("m1", index))

        assertTrue(receiver.handleInfo("a", header("m1")))
        assertEquals(0, receiver.pendingCount())
    }

    private fun header(
        messageId: String,
        sha256: ByteArray = sha256(payload),
        expiresAt: Long = 0L,
    ): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(messageId))
            .setSentAtUnixMs(now)
            .setCounter(1)
            .setExpiresAtUnixMs(expiresAt)
            .setAttachmentInfo(
                AttachmentInfo.newBuilder()
                    .setTransferId(ByteString.copyFromUtf8(messageId))
                    .setFileName("photo.jpg")
                    .setMimeType("image/jpeg")
                    .setTotalSize(payload.size.toLong())
                    .setChunkCount(chunkCount)
                    .setKind(AttachmentKind.ATTACHMENT_KIND_IMAGE)
                    .setSha256(ByteString.copyFrom(sha256)),
            )
            .build()

    private fun chunk(messageId: String, index: Int, data: ByteArray = slice(index)): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("$messageId-c$index"))
            .setSentAtUnixMs(now)
            .setCounter(1)
            .setAttachmentChunk(
                AttachmentChunk.newBuilder()
                    .setTransferId(ByteString.copyFromUtf8(messageId))
                    .setIndex(index)
                    .setData(ByteString.copyFrom(data)),
            )
            .build()

    private fun slice(index: Int): ByteArray {
        val from = index * AttachmentSender.CHUNK_BYTES
        val to = minOf(from + AttachmentSender.CHUNK_BYTES, payload.size)
        return payload.copyOfRange(from, to)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
