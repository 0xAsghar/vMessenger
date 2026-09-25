package ir.vmessenger.core.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** [SshConnector] over sshj: 15 s to connect, a keepalive every 15 s. */
class SshjConnector(private val config: () -> DefaultConfig = ::DefaultConfig) : SshConnector {

    override fun probeHostKey(target: SshTarget): HostKey {
        var seen: HostKey? = null
        val client = SSHClient(config())
        try {
            client.addHostKeyVerifier(
                Capture {
                    seen = it
                    true
                },
            )
            open(client, target)
        } finally {
            runCatching { client.disconnect() }
        }
        return seen ?: throw SshException.Disconnected(null)
    }

    override fun connect(target: SshTarget, auth: SshAuth, expected: HostKey): SshSession {
        var seen: HostKey? = null
        val client = SSHClient(config())
        client.addHostKeyVerifier(
            Capture {
                seen = it
                it == expected
            },
        )
        try {
            open(client, target)
        } catch (e: TransportException) {
            runCatching { client.disconnect() }
            if (seen != null && seen != expected) throw SshException.HostKeyMismatch(expected, seen)
            throw SshException.Disconnected(e)
        }
        try {
            authenticate(client, target.user, auth)
        } catch (e: UserAuthException) {
            val allowed = client.userAuth?.allowedMethods?.toList().orEmpty()
            runCatching { client.disconnect() }
            throw SshException.AuthRejected(allowed, e)
        } catch (e: TransportException) {
            runCatching { client.disconnect() }
            throw SshException.Disconnected(e)
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            throw if (auth is SshAuth.Key) SshException.KeyUnreadable(e) else SshException.Disconnected(e)
        }
        return SshjSession(client)
    }

    private fun open(client: SSHClient, target: SshTarget) {
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = SOCKET_TIMEOUT_MS
        try {
            client.connect(target.host, target.port)
        } catch (e: IOException) {
            throw when (e) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException -> SshException.Unreachable(e)
                is SocketTimeoutException -> SshException.Timeout(e)
                else -> e
            }
        }
        client.connection.keepAlive.keepAliveInterval = KEEPALIVE_S
    }

    private fun authenticate(client: SSHClient, user: String, auth: SshAuth) {
        when (auth) {
            // Password, then keyboard-interactive answering with the same password: servers that
            // turned PasswordAuthentication off often still take the password this way.
            is SshAuth.Password -> {
                val finder = OneSecret(auth.password)
                client.auth(user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
            }
            is SshAuth.Key -> {
                val finder = auth.passphrase?.let(::OneSecret)
                val keys = try {
                    client.loadKeys(auth.privateKey.toString(Charsets.UTF_8), null, finder)
                } catch (e: IOException) {
                    throw SshException.KeyUnreadable(e)
                }
                client.authPublickey(user, keys)
            }
        }
    }

    private class Capture(private val onKey: (HostKey) -> Boolean) : HostKeyVerifier {
        override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean = onKey(hostKeyOf(key))

        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
    }

    /** Hands sshj the same secret each time without turning it into a String. */
    private class OneSecret(private val secret: CharArray) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = secret.copyOf()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val KEEPALIVE_S = 15

        /** OpenSSH's `SHA256:` fingerprint of [key]. */
        fun hostKeyOf(key: PublicKey): HostKey {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
            return HostKey(KeyType.fromKey(key).toString(), fingerprint)
        }
    }
}
