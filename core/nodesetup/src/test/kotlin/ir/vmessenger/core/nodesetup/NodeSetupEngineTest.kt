package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.ssh.HostKey
import ir.vmessenger.core.ssh.SshAuth
import ir.vmessenger.core.ssh.SshConnector
import ir.vmessenger.core.ssh.SshException
import ir.vmessenger.core.ssh.SshResult
import ir.vmessenger.core.ssh.SshSession
import ir.vmessenger.core.ssh.SshTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.security.MessageDigest

class NodeSetupEngineTest {
    private val key = HostKey("ssh-ed25519", "SHA256:server")
    private val runId = "20260925-120000-abcd"
    private val log = listOf(
        "##vm v=1 seq=1 ts=1 ev=hello proto=1 run=$runId action=run",
        "##vm v=1 seq=2 ts=1 ev=step id=apt state=start",
        "apt says things",
        "##vm v=1 seq=3 ts=1 ev=issue code=APT_REPO_EXCLUDED severity=info step=apt detail=x",
        "##vm v=1 seq=4 ts=1 ev=step id=apt state=ok",
        "##vm v=1 seq=5 ts=1 ev=end status=0",
    ).joinToString("\n", postfix = "\n").toByteArray()

    @Test
    fun `a whole setup - host key, login, upload, preflight, run, result, verify`() = runBlocking {
        val server = FakeServer(log)
        val host = RecordingHost()
        val auth = SshAuth.Password("pw".toCharArray())
        val end = engine(server).run(request(auth), host)
        assertTrue("ended $end", end is NodeSetupState.Done)
        assertEquals(key, host.confirmed)
        assertTrue("uploads ${server.uploads}", server.uploads.any { it.endsWith("/setup-node.sh") })
        assertTrue(server.commands.any { "--preflight" in it } && server.commands.any { "--launch" in it })
        val installing = host.states.filterIsInstance<NodeSetupState.Installing>().last()
        assertEquals(StepState.OK, installing.steps[StepId.APT])
        assertEquals(IssueCode.APT_REPO_EXCLUDED, installing.issues.single().known)
        assertEquals(listOf("apt says things"), installing.logTail)
        assertTrue("the password is wiped", auth.password.all { it == '\u0000' })
    }

    @Test
    fun `a dropped follow logs in again and resumes after the last whole line`() = runBlocking {
        val cut = log.indexOfFirst { it == '\n'.code.toByte() } + 10 // mid-way through line 2
        val server = FakeServer(log, dropAfter = cut)
        val end = engine(server).run(request(), RecordingHost())
        assertTrue("ended $end", end is NodeSetupState.Done)
        val follows = server.commands.filter { "--follow" in it }
        assertEquals(2, follows.size)
        val firstLine = log.indexOfFirst { it == '\n'.code.toByte() } + 1
        assertTrue(follows[1], follows[1].contains("'--from-byte' '$firstLine'"))
    }

    @Test
    fun `a changed host key stops before any login`() = runBlocking {
        val server = FakeServer(log)
        val end = engine(server).run(request(known = HostKey("ssh-ed25519", "SHA256:other")), RecordingHost())
        assertEquals(IssueCode.SSH_HOST_KEY_MISMATCH, (end as NodeSetupState.Failed).issue.known)
        assertEquals(0, server.logins)
    }

    @Test
    fun `a host key the person does not confirm ends it, with nothing sent`() = runBlocking {
        val server = FakeServer(log)
        val end = engine(server).run(request(), RecordingHost(confirm = false))
        assertEquals(IssueCode.CANCELLED, (end as NodeSetupState.Failed).issue.known)
        assertEquals(0, server.logins)
    }

    @Test
    fun `preflight decisions are asked and passed on as --allow`() = runBlocking {
        val server = FakeServer(log, preflight = listOf(10, 0))
        val host = RecordingHost(allow = setOf("OS_UNTESTED"))
        val end = engine(server).run(request(), host)
        assertTrue("ended $end", end is NodeSetupState.Done)
        assertEquals(listOf("OS_UNTESTED"), host.asked.map { it.code })
        assertTrue(server.commands.last { "--launch" in it }.contains("'--allow' 'OS_UNTESTED'"))
    }

    private fun engine(server: FakeServer) = NodeSetupEngine(
        connector = server,
        bundle = FakeBundle,
        verifier = { Reachability.Ok },
        reconnect = ReconnectPolicy(),
    )

    private fun request(
        auth: SshAuth = SshAuth.Password("pw".toCharArray()),
        known: HostKey? = null,
    ) = NodeSetupRequest(
        target = SshTarget("203.0.113.10", 22, "alice"),
        auth = auth,
        options = InstallOptions(publicHost = "203.0.113.10", sshUser = "alice"),
        knownHostKey = known,
    )

    private inner class FakeServer(
        private val runLog: ByteArray,
        private val dropAfter: Int? = null,
        preflight: List<Int> = listOf(0),
    ) : SshConnector {
        val commands = mutableListOf<String>()
        val uploads = mutableListOf<String>()
        var logins = 0
        private val preflightExits = ArrayDeque(preflight)
        private var dropped = false

        override fun probeHostKey(target: SshTarget) = key

        override fun connect(target: SshTarget, auth: SshAuth, expected: HostKey): SshSession {
            logins++
            return Session()
        }

        inner class Session : SshSession {
            override val remoteAddress = "203.0.113.10"

            override fun run(command: String, stdin: ByteArray?, pty: Boolean, timeoutSeconds: Long): SshResult {
                commands += command
                val out = when {
                    command == ServerProbeCommand -> "1000\n/home/alice\n1000\nSUDO_OK\n"
                    "--preflight" in command -> preflightOutput()
                    "--launch" in command -> "##vm v=1 seq=2 ts=1 ev=launched run=$runId\n"
                    "--result" in command -> InstallResultTest.pinnedJson().replace(
                        "\"2.0.0\"",
                        "\"${FakeBundle.nodeVersion}\""
                    )
                    else -> ""
                }
                val exit = if ("--preflight" in command) lastPreflight else 0
                return SshResult(exit, out.toByteArray(), ByteArray(0))
            }

            private var lastPreflight = 0

            private fun preflightOutput(): String {
                lastPreflight = preflightExits.removeFirstOrNull() ?: 0
                return if (lastPreflight == 10) {
                    "##vm v=1 seq=3 ts=1 ev=issue code=OS_UNTESTED severity=consent step=preflight detail=new\n"
                } else {
                    "##vm v=1 seq=3 ts=1 ev=end status=0\n"
                }
            }

            override fun stream(command: String, stdin: ByteArray?, onOutput: (ByteArray) -> Unit): Int {
                commands += command
                val from = Regex("'--from-byte' '(\\d+)'").find(command)!!.groupValues[1].toInt()
                if (dropAfter != null && !dropped) {
                    dropped = true
                    onOutput(runLog.copyOfRange(from, dropAfter))
                    throw SshException.Disconnected(null)
                }
                onOutput(runLog.copyOfRange(from, runLog.size))
                return 0
            }

            override fun upload(source: () -> InputStream, length: Long, remotePath: String) {
                uploads += remotePath
            }

            override fun close() = Unit
        }
    }

    private class RecordingHost(
        private val confirm: Boolean = true,
        private val allow: Set<String> = emptySet(),
    ) : SetupHost {
        val states = mutableListOf<NodeSetupState>()
        var confirmed: HostKey? = null
        val asked = mutableListOf<Issue>()

        override fun onState(state: NodeSetupState) {
            states += state
        }

        override suspend fun confirmHostKey(key: HostKey): Boolean {
            confirmed = key
            return confirm
        }

        override suspend fun sudoPassword(wrong: Boolean): CharArray? = null

        override suspend fun decide(issues: List<Issue>): Set<String> {
            asked += issues
            return allow
        }
    }

    private object FakeBundle : InstallerBundle {
        override val nodeVersion = "2.0.0"
        override val protocol = INSTALLER_PROTOCOL
        override val files = listOf(
            "setup-node.sh" to "#!/bin/bash\n",
            "deploy/nginx/a.conf" to "x"
        ).map { (path, text) ->
            val bytes = text.toByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            BundleFile(path, sha, bytes.size.toLong()) { bytes.inputStream() }
        }
    }

    private companion object {
        val ServerProbeCommand = ServerProbe.COMMAND
    }
}
