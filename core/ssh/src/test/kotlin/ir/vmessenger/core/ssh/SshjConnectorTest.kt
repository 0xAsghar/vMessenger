package ir.vmessenger.core.ssh

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A real SSH server (Apache MINA SSHD) running real commands on this machine. */
class SshjConnectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: SshServer
    private val connector = SshjConnector()
    private val password = "correct horse"

    @Before
    fun start() {
        server = serve(sftp = true)
    }

    @After
    fun stop() {
        server.stop(true)
    }

    @Test
    fun `the host key is learned without logging in, and is what the server holds`() {
        val key = connector.probeHostKey(target())
        val serverKey = server.keyPairProvider.loadKeys(null).first().public
        assertEquals(SshjConnector.hostKeyOf(serverKey), key)
        assertTrue(key.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun `a server with another host key is refused before any login`() {
        val wrong = HostKey("ssh-ed25519", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val error = runCatching { connector.connect(target(), SshAuth.Password(password.toCharArray()), wrong) }
            .exceptionOrNull()
        assertTrue("got $error", error is SshException.HostKeyMismatch)
        assertEquals(connector.probeHostKey(target()), (error as SshException.HostKeyMismatch).actual)
    }

    @Test
    fun `a password logs in and commands report output and exit status`() {
        connect(SshAuth.Password(password.toCharArray())).use { ssh ->
            assertEquals("hi\n", ssh.run("echo hi").stdoutText)
            assertEquals(3, ssh.run("exit 3").exitStatus)
            assertEquals("abc", ssh.run("cat", stdin = "abc".toByteArray()).stdoutText)
            assertEquals("err\n", ssh.run("echo err >&2").stderrText)
        }
    }

    @Test
    fun `a wrong password is an auth rejection naming what the server accepts`() {
        val error = runCatching { connect(SshAuth.Password("nope".toCharArray())) }.exceptionOrNull()
        assertTrue("got $error", error is SshException.AuthRejected)
    }

    @Test
    fun `keys log in - ed25519 with a passphrase, RSA PEM, ECDSA`() {
        val cases = listOf(
            keygen("ed25519", "secret passphrase") to "secret passphrase",
            keygen("rsa", "", "-b", "2048", "-m", "PEM") to null,
            keygen("ecdsa", "", "-b", "256") to null,
        )
        for ((key, passphrase) in cases) {
            connect(SshAuth.Key(key.readBytes(), passphrase?.toCharArray())).use { ssh ->
                assertEquals("ok\n", ssh.run("echo ok").stdoutText)
            }
        }
    }

    @Test
    fun `a key that is not a key is unreadable`() {
        val error = runCatching { connect(SshAuth.Key("not a key".toByteArray())) }.exceptionOrNull()
        assertTrue("got $error", error is SshException.KeyUnreadable)
    }

    @Test
    fun `output streams as it arrives`() {
        connect(SshAuth.Password(password.toCharArray())).use { ssh ->
            val seen = StringBuilder()
            val status = ssh.stream("for i in 1 2 3; do echo \$i; sleep 0.1; done") {
                seen.append(it.toString(Charsets.UTF_8))
            }
            assertEquals(0, status)
            assertEquals("1\n2\n3\n", seen.toString())
        }
    }

    @Test
    fun `uploads go over SFTP, or cat where SFTP is off`() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        for (sftp in listOf(true, false)) {
            server.stop(true)
            server = serve(sftp)
            val dest = File(folder.root, "upload-$sftp.bin")
            connect(SshAuth.Password(password.toCharArray())).use { ssh ->
                ssh.upload({ payload.inputStream() }, payload.size.toLong(), dest.absolutePath)
            }
            assertArrayEquals(payload, dest.readBytes())
        }
    }

    @Test
    fun `secrets wipe and never print`() {
        val auth = SshAuth.Password("hunter2".toCharArray())
        assertEquals("SshAuth.Password(***)", auth.toString())
        auth.wipe()
        assertTrue(auth.password.all { it == '\u0000' })
    }

    private fun target() = SshTarget("127.0.0.1", server.port, System.getProperty("user.name"))

    private fun connect(auth: SshAuth): SshSession = connector.connect(target(), auth, connector.probeHostKey(target()))

    private fun serve(sftp: Boolean): SshServer = SshServer.setUpDefaultServer().apply {
        port = 0
        keyPairProvider = hostKeys
        passwordAuthenticator = PasswordAuthenticator { _, given, _ -> given == password }
        publickeyAuthenticator = AuthorizedKeysAuthenticator(authorizedKeys.toPath())
        // Through a shell, as sshd runs them: pipes, redirections, `exit`, loops.
        commandFactory = CommandFactory { channel, command ->
            ProcessShellFactory(command, listOf("/bin/sh", "-c", command)).createShell(channel)
        }
        if (sftp) subsystemFactories = listOf(SftpSubsystemFactory())
        start()
    }

    private val hostKeys: KeyPairProvider by lazy {
        SimpleGeneratorHostKeyProvider(File(folder.root, "hostkey.ser").toPath())
    }

    private val authorizedKeys: File by lazy { File(folder.root, "authorized_keys").apply { createNewFile() } }

    /** A key made by the real ssh-keygen, so the formats are the ones people have. */
    private fun keygen(type: String, passphrase: String, vararg extra: String): File {
        val file = File(folder.root, "id_$type")
        val process = ProcessBuilder(listOf("ssh-keygen", "-q", "-t", type, "-N", passphrase, "-f", file.path) + extra)
            .redirectErrorStream(true).start()
        assertEquals(0, process.waitFor())
        authorizedKeys.appendText(File(file.path + ".pub").readText())
        assertNotNull(file.readText())
        return file
    }
}
