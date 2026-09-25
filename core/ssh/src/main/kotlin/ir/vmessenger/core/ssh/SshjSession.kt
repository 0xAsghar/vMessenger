package ir.vmessenger.core.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.xfer.InMemorySourceFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class SshjSession(private val client: SSHClient) : SshSession {

    override val remoteAddress: String = client.remoteAddress?.hostAddress.orEmpty()

    override fun run(command: String, stdin: ByteArray?, pty: Boolean, timeoutSeconds: Long): SshResult = guarded {
        client.startSession().use { session ->
            if (pty) session.allocateDefaultPTY()
            val cmd = session.exec(command)
            val stderr = ByteArrayOutputStream()
            val errReader = thread(isDaemon = true) { cmd.errorStream.copyTo(stderr) }
            write(cmd, stdin)
            val stdout = cmd.inputStream.readBytes()
            errReader.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
            cmd.join(timeoutSeconds, TimeUnit.SECONDS)
            SshResult(cmd.exitStatus ?: -1, stdout, stderr.toByteArray())
        }
    }

    override fun stream(command: String, stdin: ByteArray?, onOutput: (ByteArray) -> Unit): Int = guarded {
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val errReader = thread(isDaemon = true) { cmd.errorStream.copyTo(ByteArrayOutputStream()) }
            write(cmd, stdin)
            val buffer = ByteArray(BUFFER_SIZE)
            val input = cmd.inputStream
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) onOutput(buffer.copyOf(read))
            }
            errReader.join(JOIN_MS)
            cmd.join(JOIN_S, TimeUnit.SECONDS)
            cmd.exitStatus ?: -1
        }
    }

    override fun upload(source: () -> InputStream, length: Long, remotePath: String) = guarded {
        try {
            client.newSFTPClient().use { sftp ->
                sftp.put(Source(source, length, remotePath.substringAfterLast('/')), remotePath)
            }
        } catch (e: TransportException) {
            throw e
        } catch (@Suppress("SwallowedException") e: IOException) {
            // Some servers run with the SFTP subsystem off; a plain `cat >` works wherever a shell does.
            catUpload(source, remotePath)
        }
    }

    private fun catUpload(source: () -> InputStream, remotePath: String) {
        client.startSession().use { session ->
            val cmd = session.exec("cat > '${remotePath.replace("'", "'\\''")}'")
            source().use { it.copyTo(cmd.outputStream) }
            cmd.outputStream.close()
            cmd.join(UPLOAD_JOIN_S, TimeUnit.SECONDS)
            if (cmd.exitStatus != 0) throw SFTPException("cat upload to $remotePath exited ${cmd.exitStatus}")
        }
    }

    private fun write(cmd: Session.Command, stdin: ByteArray?) {
        if (stdin != null) {
            cmd.outputStream.write(stdin)
            cmd.outputStream.flush()
        }
        cmd.outputStream.close()
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SshException) {
        throw e
    } catch (e: ConnectionException) {
        throw SshException.Disconnected(e)
    } catch (e: TransportException) {
        throw SshException.Disconnected(e)
    } catch (e: IOException) {
        if (!client.isConnected) throw SshException.Disconnected(e)
        throw e
    }

    override fun close() {
        runCatching { client.disconnect() }
    }

    private class Source(private val open: () -> InputStream, private val size: Long, private val name: String) :
        InMemorySourceFile() {
        override fun getName(): String = name
        override fun getLength(): Long = size
        override fun getInputStream(): InputStream = open()
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val JOIN_MS = 5_000L
        const val JOIN_S = 5L
        const val UPLOAD_JOIN_S = 60L
    }
}
