package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.location.LocationUpdate
import ir.vmessenger.core.proto.app.v1.Control
import ir.vmessenger.core.proto.app.v1.ControlType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.network.LocationFixtures.locationEnvelope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocationSharingCoordinatorTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val peerB = InboundFixtures.peer(0x0B)
    private val peerC = InboundFixtures.peer(0x0C)

    private lateinit var harness: CleanupHarness
    private lateinit var coordinator: LocationSharingCoordinator

    @Before
    fun setUp() {
        harness = CleanupHarness()
        coordinator = harness.locationSharing
    }

    @Test
    fun inboundFromBlockedIgnored() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA, blocked = true)

        coordinator.handleIncomingLocation("a", locationEnvelope("s1"))

        assertTrue(harness.shareDao.shares.isEmpty())
        assertTrue(harness.sampleDao.samples.isEmpty())
    }

    @Test
    fun inboundFromPendingContactIgnored() = runTest {
        harness.contactDao.contacts +=
            InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)

        coordinator.handleIncomingLocation("a", locationEnvelope("s1"))
        coordinator.handleIncomingControl("a", controlEnvelope(ControlType.CONTROL_TYPE_LOCATION_SHARE_STOP))

        assertTrue(harness.shareDao.shares.isEmpty())
        assertTrue(harness.sampleDao.samples.isEmpty())
    }

    @Test
    fun newShareIdReplacesOld() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)

        coordinator.handleIncomingLocation("a", locationEnvelope("s1"))
        coordinator.handleIncomingLocation("a", locationEnvelope("s2"))

        assertEquals(listOf("s2"), harness.shareDao.activeIds("a", MessageDirection.INCOMING))
        assertFalse(harness.shareDao.getById("s1")!!.active)
        assertEquals(1, harness.sampleDao.forShare("s1").size)
        assertEquals(1, harness.sampleDao.forShare("s2").size)

        // A late packet for the replaced share is dropped rather than resurrecting it.
        coordinator.handleIncomingLocation("a", locationEnvelope("s1", sampledAtUnixMs = 99L))

        assertEquals(1, harness.sampleDao.forShare("s1").size)
        assertFalse(harness.shareDao.getById("s1")!!.active)
    }

    @Test
    fun shareIdOfAnotherContactRejected() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)

        coordinator.handleIncomingLocation("a", locationEnvelope("s1"))
        coordinator.handleIncomingLocation("b", locationEnvelope("s1"))

        assertEquals("a", harness.shareDao.getById("s1")!!.contactId)
        assertEquals(1, harness.sampleDao.forShare("s1").size)
        assertTrue(harness.shareDao.activeIds("b", MessageDirection.INCOMING).isEmpty())
    }

    @Test
    fun samplesRateLimited() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)

        coordinator.handleIncomingLocation("a", locationEnvelope("s1", latitude = 1.0))
        coordinator.handleIncomingLocation("a", locationEnvelope("s1", latitude = 2.0))
        coordinator.handleIncomingLocation("a", locationEnvelope("s1", latitude = 3.0))

        val recorded = harness.sampleDao.forShare("s1")
        assertEquals(1, recorded.size)
        assertEquals(1.0, recorded.single().latitude, 0.0)

        val later = System.currentTimeMillis() + LocationSharingCoordinator.MIN_SAMPLE_INTERVAL_MS
        assertTrue("a sample after the interval is accepted", coordinator.acceptsSample("s1", later))
        assertFalse("the next one inside the interval is dropped", coordinator.acceptsSample("s1", later + 1))
    }

    @Test
    fun startSharesSkipBlockedAndPendingContacts() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB, blocked = true)
        harness.contactDao.contacts +=
            InboundFixtures.contact("c", peerC, status = ContactRelationshipStatus.PENDING_IN)
        harness.locationAccessRepository.granted += listOf("a", "b", "c")

        coordinator.startSharingToGrantedContacts()

        assertEquals(1, harness.shareDao.activeIds("a", MessageDirection.OUTGOING).size)
        assertTrue(harness.shareDao.activeIds("b", MessageDirection.OUTGOING).isEmpty())
        assertTrue(harness.shareDao.activeIds("c", MessageDirection.OUTGOING).isEmpty())
        assertEquals(listOf("a"), harness.messaging.sent.map { it.first })
        assertEquals(1, harness.serviceControl.starts)
    }

    @Test
    fun concurrentUpdateNoCme() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.contactDao.contacts += InboundFixtures.contact("b", peerB)
        harness.contactDao.contacts += InboundFixtures.contact("c", peerC)
        harness.locationAccessRepository.granted += listOf("a", "b", "c")
        coordinator.startSharingToGrantedContacts()
        harness.messaging.sent.clear()

        // Recording a sample yields (FakeLocationSampleDao), so the removals below
        // land in the middle of the fan-out; a live-map iteration would throw
        // ConcurrentModificationException here.
        val fanOut = launch { coordinator.onLocationUpdate(LocationUpdate(1.0, 2.0, 5f, 1_000L)) }
        val removals = launch { for (id in listOf("a", "b", "c")) coordinator.stopSharingWith(id) }
        joinAll(fanOut, removals)

        assertEquals(3, harness.messaging.sent.count { it.second.hasLocation() })
        assertTrue(harness.shareDao.shares.none { it.active })
        assertEquals("service stops once the last outgoing share is gone", 1, harness.serviceControl.stops)
    }

    @Test
    fun stopSharingWithEndsBothDirectionsSilently() = runTest {
        harness.contactDao.contacts += InboundFixtures.contact("a", peerA)
        harness.locationAccessRepository.granted += "a"
        coordinator.startSharingToGrantedContacts()
        coordinator.handleIncomingLocation("a", locationEnvelope("in-1"))
        harness.messaging.sent.clear()

        coordinator.stopSharingWith("a")

        assertTrue(harness.shareDao.shares.none { it.active })
        assertTrue("blocked/deleted contacts are not notified", harness.messaging.sent.isEmpty())
    }

    private fun controlEnvelope(type: ControlType) = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("ctl-${type.name}"))
        .setSentAtUnixMs(1L)
        .setCounter(1)
        .setControl(Control.newBuilder().setType(type))
        .build()
}
