package ir.vmessenger.core.common

sealed class AppError(open val message: String) {
    data class Unknown(override val message: String) : AppError(message)
    data class Crypto(override val message: String) : AppError(message)
    data class Network(override val message: String) : AppError(message)
    data class Validation(override val message: String) : AppError(message)
    data class NotFound(override val message: String) : AppError(message)
    data class Security(override val message: String) : AppError(message)

    /** The peer speaks a different protocol major ([peerMajor]); the user must update one of the apps. */
    data class ProtocolVersion(override val message: String, val peerMajor: Int) : AppError(message)
}
