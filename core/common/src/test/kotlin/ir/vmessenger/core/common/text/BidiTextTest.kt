package ir.vmessenger.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTextTest {

    @Test
    fun `wraps a name in a first-strong isolate`() {
        val isolated = BidiText.isolate("Ali")
        assertEquals("⁨Ali⁩", isolated)
    }

    @Test
    fun `leaves an empty string alone rather than emitting a bare isolate pair`() {
        assertEquals("", BidiText.isolate(""))
    }

    @Test
    fun `the isolated run does not become the paragraph's first strong character`() {
        // The template that made this necessary: the name leads a Persian sentence.
        val line = "${BidiText.isolate("Ali")} به گروه اضافه شد"
        assertTrue("the isolate must precede the name", line.startsWith('⁨'))
        assertTrue("the isolate must be popped before the Persian run", line.contains("⁩ به"))
    }
}
