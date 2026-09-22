package ir.vmessenger.data.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transition table, and above all the one property it exists to guarantee: audio is captured in
 * exactly one state, which is only reachable by someone answering.
 */
class CallStateTest {

    @Test
    fun `the microphone is open in exactly one state`() {
        val open = CallState.entries.filter { it.microphoneOpen }

        assertEquals(listOf(CallState.Active), open)
    }

    @Test
    fun `ringing never opens the microphone`() {
        // The claim worth pinning: a call that is merely offered cannot be listening.
        assertFalse(CallState.IncomingRinging.microphoneOpen)
        assertFalse(CallState.OutgoingRinging.microphoneOpen)
        assertFalse(CallState.Connecting.microphoneOpen)
        assertFalse(CallState.Reconnecting.microphoneOpen)
    }

    @Test
    fun `an incoming call reaches audio only through an accept`() {
        var state = CallState.Idle

        state = state.next(CallEvent.InviteReceived)!!
        assertEquals(CallState.IncomingRinging, state)
        // Media arriving before anyone answered must not promote the call.
        assertNull(state.next(CallEvent.MediaUp))

        state = state.next(CallEvent.AcceptedHere)!!
        assertEquals(CallState.Connecting, state)
        assertFalse(state.microphoneOpen)

        state = state.next(CallEvent.MediaUp)!!
        assertTrue(state.microphoneOpen)
    }

    @Test
    fun `an outgoing call waits for the peer to accept`() {
        var state = CallState.Idle.next(CallEvent.DialOut)!!
        assertEquals(CallState.OutgoingRinging, state)

        // Their device alerting is worth showing but is not progress through the machine.
        assertEquals(CallState.OutgoingRinging, state.next(CallEvent.RingReceived))
        // We are the caller; an accept from this side is meaningless.
        assertNull(state.next(CallEvent.AcceptedHere))

        state = state.next(CallEvent.AcceptReceived)!!
        assertEquals(CallState.Connecting, state)
    }

    @Test
    fun `losing the media path closes the microphone until it returns`() {
        val active = CallState.Active
        assertTrue(active.microphoneOpen)

        val reconnecting = active.next(CallEvent.MediaLost)!!
        assertEquals(CallState.Reconnecting, reconnecting)
        assertFalse(reconnecting.microphoneOpen, "a stalled call must not hold the mic open")

        assertEquals(CallState.Active, reconnecting.next(CallEvent.MediaRestored))
    }

    @Test
    fun `reconnecting gives up on a timeout`() {
        assertEquals(CallState.Ending, CallState.Reconnecting.next(CallEvent.TimedOut))
    }

    @Test
    fun `either side can end a call from any live state`() {
        val live = listOf(
            CallState.OutgoingRinging,
            CallState.IncomingRinging,
            CallState.Connecting,
            CallState.Active,
            CallState.Reconnecting,
        )

        live.forEach { state ->
            assertEquals(CallState.Ending, state.next(CallEvent.EndedHere), "local hangup from $state")
            assertEquals(CallState.Ending, state.next(CallEvent.EndedByPeer), "peer hangup from $state")
        }
    }

    @Test
    fun `a signal that loses a race is ignored rather than fatal`() {
        // A hangup and an accept crossing on the wire is ordinary; the loser must do nothing.
        assertNull(CallState.Ending.next(CallEvent.AcceptReceived))
        assertNull(CallState.Ending.next(CallEvent.MediaUp))
        assertNull(CallState.Idle.next(CallEvent.MediaUp))
        assertNull(CallState.Idle.next(CallEvent.EndedByPeer))
    }

    @Test
    fun `only live states put a call on screen`() {
        assertFalse(CallState.Idle.onScreen)
        assertFalse(CallState.Ending.onScreen)
        assertTrue(CallState.IncomingRinging.onScreen)
        assertTrue(CallState.Active.onScreen)
    }
}
