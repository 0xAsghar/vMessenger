package ir.vmessenger.core.nodesetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The installer and this engine speak the same protocol: versions, issue codes, step ids. */
class ContractTest {
    // Comment lines describe the grammar ("die CODE MESSAGE"); only code counts.
    private val script = File("../../scripts/setup-node.sh").readLines()
        .filterNot { it.trimStart().startsWith("#") }
        .joinToString("\n")

    @Test
    fun `protocol versions agree`() {
        val version = Regex("(?m)^readonly PROTOCOL_VERSION=(\\d+)$").find(script)!!.groupValues[1].toInt()
        assertEquals(INSTALLER_PROTOCOL, version)
    }

    @Test
    fun `every issue code the installer can report is known here`() {
        val codes = Regex("""\b(?:die|issue|consent)\s+([A-Z][A-Z0-9_]+)\b""").findAll(script).map {
            it.groupValues[1]
        }.toSet() +
            Regex("""code=([A-Z][A-Z0-9_]+)""").findAll(script).map { it.groupValues[1] }.toSet() +
            Regex("""FATAL_CODE="([A-Z][A-Z0-9_]+)"""").findAll(script).map { it.groupValues[1] }.toSet()
        val unknown = codes.filter { IssueCode.of(it) == null }
        assertTrue("codes missing from IssueCode: $unknown", unknown.isEmpty())
        assertTrue(codes.size > 40)
    }

    @Test
    fun `every step the installer reports is known here`() {
        val steps = Regex("""\bstep\s+([a-z0-9_]+)\s+(?:start|ok|skip|warn|fail|wait)\b""").findAll(script)
            .map { it.groupValues[1] }.toSet()
        val unknown = steps.filter { StepId.of(it) == null }
        assertTrue("steps missing from StepId: $unknown", unknown.isEmpty())
        assertTrue(steps.size > 10)
    }
}
