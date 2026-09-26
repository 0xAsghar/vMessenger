package ir.vmessenger.domain.repository

/** Lets a change to the node list take effect now rather than at the next reconnect or start. */
interface RelayControl {
    /**
     * Reconnects the relay listener to whichever enabled relay ranks first now, and joins the DHT
     * again through the enabled bootstrap nodes only.
     */
    fun reselectRelay()
}
