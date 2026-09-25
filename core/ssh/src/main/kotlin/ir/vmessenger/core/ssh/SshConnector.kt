package ir.vmessenger.core.ssh

import java.io.Closeable
import java.io.InputStream

/** Opens SSH sessions. Blocking; call it off the main thread. */
interface SshConnector {
    /** The server's host key, learned without logging in — so no credential goes to an unconfirmed server. */
    fun probeHostKey(target: SshTarget): HostKey

    /** Logs in, but only to a server whose host key is [expected]. */
    fun connect(target: SshTarget, auth: SshAuth, expected: HostKey): SshSession
}

/** A logged-in connection. */
interface SshSession : Closeable {
    /** The address the socket reached (the IP the host name resolved to). */
    val remoteAddress: String

    /** Runs [command], writing [stdin] to it first, and waits for it to end. */
    fun run(
        command: String,
        stdin: ByteArray? = null,
        pty: Boolean = false,
        timeoutSeconds: Long = RUN_TIMEOUT_S,
    ): SshResult

    /**
     * Runs [command] and hands its output to [onOutput] as it arrives; returns the exit status.
     * For long commands whose progress matters (following an install's log).
     */
    fun stream(command: String, stdin: ByteArray? = null, onOutput: (ByteArray) -> Unit): Int

    /** Writes [length] bytes from [source] to [remotePath] (SFTP, or `cat >` where SFTP is off). */
    fun upload(source: () -> InputStream, length: Long, remotePath: String)

    companion object {
        const val RUN_TIMEOUT_S = 120L
    }
}
