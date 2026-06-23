package ir.vmessenger.core.common

sealed class AppError(open val message: String) {
    data class Unknown(override val message: String) : AppError(message)
    data class Crypto(override val message: String) : AppError(message)
    data class Network(override val message: String) : AppError(message)
    data class Validation(override val message: String) : AppError(message)
    data class NotFound(override val message: String) : AppError(message)
    data class Security(override val message: String) : AppError(message)
}
