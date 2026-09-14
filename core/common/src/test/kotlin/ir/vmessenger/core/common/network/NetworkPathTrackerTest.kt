package ir.vmessenger.core.common.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class NetworkPathTrackerTest {
    @Before
    fun reset() {
        NetworkPathTracker.clear()
    }

    @After
    fun tearDown() {
        NetworkPathTracker.clear()
    }

    @Test
    fun lastPathReflectsMostRecentEvent() {
        NetworkPathTracker.record(NetworkPath.DEFAULT_RELAY, "relay", atUnixMs = 1)
        NetworkPathTracker.record(NetworkPath.DIRECT, "1.2.3.4:9", atUnixMs = 2)

        val last = NetworkPathTracker.lastPath.value
        assertEquals(NetworkPath.DIRECT, last?.path)
        assertEquals("1.2.3.4:9", last?.detail)
        assertEquals(2, NetworkPathTracker.events.value.size)
        assertEquals(NetworkPath.DIRECT, NetworkPathTracker.events.value.first().path)
    }

    @Test
    fun historyIsCappedAndNewestFirst() {
        repeat(30) { i -> NetworkPathTracker.record(NetworkPath.DIRECT, "peer$i", atUnixMs = i.toLong()) }

        val events = NetworkPathTracker.events.value
        assertEquals(20, events.size)
        assertEquals("peer29", events.first().detail)
    }

    @Test
    fun clearResetsState() {
        NetworkPathTracker.record(NetworkPath.USER_RELAY, "x")
        NetworkPathTracker.clear()
        assertNull(NetworkPathTracker.lastPath.value)
        assertEquals(0, NetworkPathTracker.events.value.size)
    }

    @Test
    fun chainValidationFailureRaisesClockWarning() {
        // Exactly what Conscrypt surfaces when the device clock is outside the
        // relay certificate's validity window.
        NetworkPathTracker.reportConnectionError(SSLHandshakeException("Chain validation failed"))
        assertEquals(ListenerAlert.CLOCK_CERTIFICATE, NetworkPathTracker.listenerAlert.value)
    }

    @Test
    fun certExceptionInCauseChainRaisesClockWarning() {
        val wrapped = RuntimeException("relay dial failed", CertPathValidatorException("timestamp check failed"))
        NetworkPathTracker.reportConnectionError(wrapped)
        assertEquals(ListenerAlert.CLOCK_CERTIFICATE, NetworkPathTracker.listenerAlert.value)
    }

    @Test
    fun ordinaryFailureDoesNotRaiseClockWarning() {
        NetworkPathTracker.reportConnectionError(java.net.SocketTimeoutException("timeout"))
        NetworkPathTracker.reportConnectionError(java.io.IOException("Software caused connection abort"))
        assertEquals(ListenerAlert.NONE, NetworkPathTracker.listenerAlert.value)
    }

    @Test
    fun successClearsClockWarning() {
        NetworkPathTracker.reportConnectionError(SSLHandshakeException("Chain validation failed"))
        assertEquals(ListenerAlert.CLOCK_CERTIFICATE, NetworkPathTracker.listenerAlert.value)
        NetworkPathTracker.reportConnectionSuccess()
        assertEquals(ListenerAlert.NONE, NetworkPathTracker.listenerAlert.value)
    }

    @Test
    fun staleListenerProofRaisesClockSkew() {
        NetworkPathTracker.reportListenerRejected(RelayRejection.STALE_LISTENER_PROOF)
        assertEquals(ListenerAlert.CLOCK_SKEW, NetworkPathTracker.listenerAlert.value)
    }

    @Test
    fun otherRelayRejectionsAreLeftToTheRetryLoop() {
        NetworkPathTracker.reportListenerRejected("Relay full")
        NetworkPathTracker.reportListenerRejected("Too many listeners from this address")
        assertEquals(ListenerAlert.NONE, NetworkPathTracker.listenerAlert.value)
    }

    /**
     * A clock a few minutes out still passes certificate validation, so the DHT
     * reporting a working connection must not retire the skew hint. Only the relay
     * keeping the listener registration proves the clock.
     */
    @Test
    fun connectionSuccessDoesNotClearClockSkew() {
        NetworkPathTracker.reportListenerRejected(RelayRejection.STALE_LISTENER_PROOF)
        NetworkPathTracker.reportConnectionSuccess()
        assertEquals(ListenerAlert.CLOCK_SKEW, NetworkPathTracker.listenerAlert.value)

        NetworkPathTracker.reportListenerAccepted()
        assertEquals(ListenerAlert.NONE, NetworkPathTracker.listenerAlert.value)
    }

    @Test
    fun takeoverOutranksTheClockHintsUntilTheSlotComesBack() {
        NetworkPathTracker.reportListenerReplaced()
        // While the other device holds the slot, everything else failing here is a
        // symptom of that; pointing the user at their clock would waste their time.
        NetworkPathTracker.reportConnectionError(SSLHandshakeException("Chain validation failed"))
        NetworkPathTracker.reportListenerRejected(RelayRejection.STALE_LISTENER_PROOF)
        NetworkPathTracker.reportConnectionSuccess()
        assertEquals(ListenerAlert.IDENTITY_ELSEWHERE, NetworkPathTracker.listenerAlert.value)

        NetworkPathTracker.reportListenerAccepted()
        assertEquals(ListenerAlert.NONE, NetworkPathTracker.listenerAlert.value)
    }
}
