package ir.vmessenger.core.ssh

/** Where to log in: a server and an account on it. */
data class SshTarget(val host: String, val port: Int = DEFAULT_PORT, val user: String) {
    companion object {
        const val DEFAULT_PORT = 22
    }
}

/**
 * A server's host key as OpenSSH shows it: algorithm and `SHA256:<base64, no padding>` of the key
 * blob — what `ssh-keygen -lf` and the first-connection prompt print, so a person can compare.
 */
data class HostKey(val algorithm: String, val fingerprint: String)

/**
 * How to log in. Secrets are char and byte arrays so they can be zeroed ([wipe]) once they are no
 * longer needed; nothing here is ever written anywhere, and [toString] never shows them.
 */
sealed class SshAuth {
    abstract fun wipe()

    class Password(val password: CharArray) : SshAuth() {
        override fun wipe() = password.fill('\u0000')
        override fun toString(): String = "SshAuth.Password(***)"
    }

    /** A private key file's bytes (OpenSSH or PEM), and its passphrase if it has one. */
    class Key(val privateKey: ByteArray, val passphrase: CharArray? = null) : SshAuth() {
        override fun wipe() {
            privateKey.fill(0)
            passphrase?.fill('\u0000')
        }

        override fun toString(): String = "SshAuth.Key(***)"
    }
}

/** What a command returned. */
class SshResult(val exitStatus: Int, val stdout: ByteArray, val stderr: ByteArray) {
    val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
    val stderrText: String get() = stderr.toString(Charsets.UTF_8)
}

/** Why SSH failed, in terms a setup screen can act on. */
sealed class SshException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(cause: Throwable?) : SshException("the server does not answer", cause)
    class Timeout(cause: Throwable?) : SshException("the server stopped answering", cause)
    class HostKeyMismatch(val expected: HostKey, val actual: HostKey?) :
        SshException("the server's host key is not the one confirmed")
    class AuthRejected(val allowedMethods: List<String>, cause: Throwable? = null) :
        SshException("the server refused the login (it accepts: ${allowedMethods.joinToString()})", cause)
    class KeyUnreadable(cause: Throwable?) : SshException("the private key cannot be read", cause)
    class Disconnected(cause: Throwable?) : SshException("the connection dropped", cause)
}
