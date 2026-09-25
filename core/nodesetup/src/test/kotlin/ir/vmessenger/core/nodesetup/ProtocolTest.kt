package ir.vmessenger.core.nodesetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test
    fun `markers parse and decode, log text does not`() {
        val marker = MarkerParser.parse(
            "##vm v=1 seq=7 ts=123 ev=issue code=APT_REPO_EXCLUDED severity=info step=apt detail=a%20b%3Dc+d%25"
        )!!
        assertEquals(7, marker.seq)
        assertEquals("issue", marker.event)
        assertEquals("a b=c+d%", marker["detail"])
        val issue = MarkerParser.issueOf(marker)!!
        assertEquals(IssueCode.APT_REPO_EXCLUDED, issue.known)
        assertEquals(Severity.INFO, issue.severity)
        assertNull(MarkerParser.parse("==> installing OS packages"))
        assertNull(MarkerParser.parse("##vm v=2 seq=1 ts=1 ev=hello"))
        assertEquals(
            null,
            MarkerParser.parse(
                "##vm v=1 seq=8 ts=1 ev=issue code=SOMETHING_NEW severity=warn"
            )!!.let(MarkerParser::issueOf)!!.known
        )
    }

    @Test
    fun `lines come whole, and the offset is just past the last one`() {
        val lines = LogLineAssembler()
        assertEquals(listOf("abc"), lines.feed("abc\nde".toByteArray()))
        assertEquals(4, lines.offset)
        assertEquals(listOf("def", ""), lines.feed("f\n\n".toByteArray()))
        assertEquals(9, lines.offset)
        assertEquals(listOf("گره"), LogLineAssembler().feed("گره\n".toByteArray()))
    }

    @Test
    fun `install options become quoted arguments, and hostile ones are refused`() {
        val options = InstallOptions(
            publicHost = "203.0.113.10",
            publicPort = 8443,
            secure = true,
            keyOnlySsh = true,
            sshUser = "alice"
        )
        assertEquals(
            listOf(
                "--public-host", "203.0.113.10", "--public-port", "8443", "--secure", "--key-only-ssh",
                "--ssh-user", "alice", "--allow", "CLOCK_SKEW,OS_UNTESTED", "--clock-offset-ms", "-5",
            ),
            options.toArgs(setOf("OS_UNTESTED", "CLOCK_SKEW"), -5),
        )
        assertEquals("'a'\\''b \$(x)'", ShellQuote.quote("a'b \$(x)"))
        assertThrows(IllegalArgumentException::class.java) { InstallOptions(publicHost = "x;rm -rf /", sshUser = "a") }
        assertThrows(
            IllegalArgumentException::class.java
        ) { InstallOptions(publicHost = "h", domain = "evil'.com", sshUser = "a") }
        assertThrows(IllegalArgumentException::class.java) { InstallOptions(publicHost = "h", sshUser = "\$(id)") }
        assertEquals(
            listOf("--domain", "node.example.com", "--acme-no-email"),
            InstallOptions(
                publicHost = "h",
                domain = "node.example.com",
                secure = false,
                sshUser = "a"
            ).toArgs(emptySet(), null).drop(4).take(3)
        )
    }

    @Test
    fun `reconnects back off to 30 s and give up after ten minutes`() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(policy::delayMs))
        assertEquals(false, policy.exhausted(10))
        assertEquals(true, policy.exhausted(30))
    }
}
