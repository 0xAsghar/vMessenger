package ir.vmessenger.core.common.network

/**
 * Wire protocol version spoken by this build. Major 2 is a clean break from
 * the 0.x line: every frame carries `Frame.version == MAJOR` and any other
 * major is answered with `CLOSE{VERSION_MISMATCH}` and dropped.
 */
object ProtocolVersion {
    const val MAJOR = 2
    const val MINOR = 0
}
