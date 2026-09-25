package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.ssh.SshResult
import ir.vmessenger.core.ssh.SshSession
import java.nio.CharBuffer

/** How commands get root on this server. */
sealed class Sudo {
    data object Root : Sudo()
    data object NoPassword : Sudo()
    class WithPassword(val password: CharArray) : Sudo()
}

/**
 * Runs the uploaded installer as root on one SSH session, the way this server allows: directly as
 * root, `sudo -n`, or `sudo -S` with the password on stdin (never on the command line).
 */
internal class PrivilegedShell(var session: SshSession, val sudoMode: Sudo, val bundleDir: String) {

    fun installer(args: List<String>): String {
        val command = ShellQuote.join(listOf("bash", "$bundleDir/setup-node.sh", "--from-app") + args)
        return when (sudoMode) {
            Sudo.Root -> command
            Sudo.NoPassword -> "sudo -n $command"
            is Sudo.WithPassword -> "sudo -S -p '' $command"
        }
    }

    fun run(command: String): SshResult = session.run(command, stdin = stdin(), timeoutSeconds = RUN_TIMEOUT_S)

    fun stream(command: String, onOutput: (ByteArray) -> Unit): Int = session.stream(command, stdin(), onOutput)

    /** The password and a newline, encoded straight from the char array: no String copy of it. */
    private fun stdin(): ByteArray? = (sudoMode as? Sudo.WithPassword)?.password?.let { password ->
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        ByteArray(encoded.remaining() + 1).also { bytes ->
            encoded.get(bytes, 0, encoded.remaining())
            bytes[bytes.size - 1] = '\n'.code.toByte()
            encoded.array().fill(0)
        }
    }

    private companion object {
        const val RUN_TIMEOUT_S = 300L
    }
}

/** What one probe of a fresh login learns: who we are, where home is, the server's clock, sudo. */
internal data class ServerProbe(
    val uid: Int,
    val home: String,
    val serverTimeMs: Long,
    val sudoWithoutPassword: Boolean
) {
    companion object {
        const val COMMAND = "id -u; printf '%s\\n' \"\$HOME\"; date +%s%3N; " +
            "if sudo -n true 2>/dev/null; then echo SUDO_OK; else echo SUDO_NO; fi"

        fun parse(output: String): ServerProbe? {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return if (lines.size < PROBE_LINES) {
                null
            } else {
                val uid = lines[0].toIntOrNull()
                val time = lines[2].toLongOrNull()
                if (uid == null || time == null) null else ServerProbe(uid, lines[1], time, lines[3] == "SUDO_OK")
            }
        }

        private const val PROBE_LINES = 4
    }
}
