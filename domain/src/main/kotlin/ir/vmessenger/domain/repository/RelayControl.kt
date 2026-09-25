package ir.vmessenger.domain.repository

/** Lets a change to the relay list take effect now rather than at the next reconnect. */
interface RelayControl {
    /** Reconnects the relay listener to whichever enabled relay ranks first now. */
    fun reselectRelay()
}
