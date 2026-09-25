package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.common.network.PinnedTls
import ir.vmessenger.core.common.network.SpkiPin
import ir.vmessenger.core.ssh.HostKey
import ir.vmessenger.core.ssh.SshAuth
import ir.vmessenger.core.ssh.SshTarget
import ir.vmessenger.core.ssh.SshjConnector
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The real engine and real SSH against a throwaway server: `scripts/provision-test/run.sh test
 * --scenario engine` starts one and runs this through `./gradlew :core:nodesetup:provisionE2e`.
 * Skipped everywhere else.
 */
class ProvisionE2eTest {
    @Test
    fun `a node is set up on a real server, key-only SSH confirmed, and reachable with its pin`() {
        val target = System.getProperty("vmE2eTarget")
        assumeTrue("run through scripts/provision-test/run.sh --scenario engine", target != null)
        val (host, port) = target!!.split(':').let { it[0] to it[1].toInt() }
        val https = System.getProperty("vmE2eHttpsPort").toInt()
        val bundle = DirectoryBundle(File(System.getProperty("vmE2eBundle")))
        val auth = SshAuth.Key(File(System.getProperty("vmE2eKey")).readBytes())
        val states = mutableListOf<NodeSetupState>()
        val engine = NodeSetupEngine(SshjConnector(), bundle, verifier = { result -> pinnedHealth(https, result.pin) })
        val end = runBlocking {
            engine.run(
                NodeSetupRequest(
                    SshTarget(host, port, "alice"),
                    auth,
                    InstallOptions(
                        publicHost = "127.0.0.1",
                        publicPort = https,
                        secure = true,
                        keyOnlySsh = true,
                        sshUser = "alice"
                    ),
                ),
                object : SetupHost {
                    override fun onState(state: NodeSetupState) {
                        states += state
                        if (state is NodeSetupState.Failed) {
                            println(
                                "failed: ${state.issue} ${state.logTail.takeLast(20)}"
                            )
                        }
                    }

                    override suspend fun confirmHostKey(key: HostKey) = true

                    override suspend fun sudoPassword(wrong: Boolean): CharArray? = null

                    override suspend fun decide(issues: List<Issue>): Set<String> = emptySet()
                },
            )
        }
        assertTrue("ended $end", end is NodeSetupState.Done)
        end as NodeSetupState.Done
        assertEquals("ip-pinned", end.result.mode)
        assertEquals("applied", end.result.keyOnlySsh)
        assertEquals(Reachability.Ok, end.reach)
        assertTrue(states.any { it is NodeSetupState.Installing })
    }

    private fun pinnedHealth(port: Int, pin: String?): Reachability {
        val client = PinnedTls.pinTo(OkHttpClient.Builder(), listOfNotNull(pin?.let(SpkiPin::parse))).build()
        val request = Request.Builder().url("https://127.0.0.1:$port/healthz").build()
        val ok = runCatching { client.newCall(request).execute().use { it.body?.string() } }
            .getOrNull()?.startsWith("ok") == true
        return if (ok) Reachability.Ok else Reachability.Problem(Issue.of(IssueCode.REACH_TLS_MISMATCH))
    }

    /** The bundle as `:app:bundleNodeInstaller` writes it. */
    private class DirectoryBundle(private val dir: File) : InstallerBundle {
        override val nodeVersion: String =
            Regex("\"nodeVersion\": \"([^\"]+)\"").find(File(dir, "manifest.json").readText())!!.groupValues[1]
        override val protocol = INSTALLER_PROTOCOL
        override val files: List<BundleFile> = dir.walkTopDown().filter { it.isFile }.map { file ->
            val sha = MessageDigest.getInstance(
                "SHA-256"
            ).digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            BundleFile(file.relativeTo(dir).invariantSeparatorsPath, sha, file.length()) { file.inputStream() }
        }.toList()
    }
}
