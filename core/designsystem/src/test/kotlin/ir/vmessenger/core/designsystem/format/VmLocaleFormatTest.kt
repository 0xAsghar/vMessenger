package ir.vmessenger.core.designsystem.format

import ir.vmessenger.core.common.text.VmLocale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The same formatters, in English.
 *
 * [VmLocale.current] is process-global, so every test here restores it. A test that left it set
 * would not fail itself — it would fail whichever test ran next, which is the worst way to find out.
 */
class VmLocaleFormatTest {

    @Before
    fun setUp() {
        VmLocale.current = VmLocale.En
    }

    @After
    fun tearDown() {
        VmLocale.current = VmLocale.Fa
    }

    @Test
    fun `english leaves ascii digits alone`() {
        assertEquals("1234567890", VmTextFormat.digits("1234567890"))
        assertEquals("version 1.0.0 (45)", VmTextFormat.digits("version 1.0.0 (45)"))
    }

    @Test
    fun `english file sizes use a dot and latin units`() {
        assertEquals("1.2 MB", VmTextFormat.fileSize(1_258_291))
        assertEquals("950 B", VmTextFormat.fileSize(950))
        // A fraction that rounds away leaves a bare integer rather than a trailing ".0".
        assertEquals("2 GB", VmTextFormat.fileSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `english decimals and lists use latin punctuation`() {
        assertEquals("1.2", VmTextFormat.oneDecimal(1.24))
        assertEquals("3", VmTextFormat.oneDecimal(2.96))
        assertEquals("Ali, Maryam", VmTextFormat.list(listOf("Ali", "Maryam")))
    }

    @Test
    fun `english percent uses the latin sign`() {
        assertEquals("42%", VmTextFormat.percent(0.42f))
        assertEquals("100%", VmTextFormat.percent(2f))
        assertEquals("0%", VmTextFormat.percent(-1f))
    }

    @Test
    fun `english durations keep ascii digits`() {
        assertEquals("0:42", VmTextFormat.duration(42_000))
        assertEquals("1:02:03", VmTextFormat.duration(3_723_000))
    }

    @Test
    fun `english relative ages pluralise on the number`() {
        assertEquals("just now", VmTextFormat.relative(0))
        assertEquals("1 minute ago", VmTextFormat.relative(VmTextFormat.MILLIS_PER_MINUTE))
        assertEquals("2 minutes ago", VmTextFormat.relative(2 * VmTextFormat.MILLIS_PER_MINUTE))
        assertEquals("1 hour ago", VmTextFormat.relative(VmTextFormat.MILLIS_PER_HOUR))
        assertEquals("3 days ago", VmTextFormat.relative(3 * VmTextFormat.MILLIS_PER_DAY))
    }

    @Test
    fun `the relative window ends in both languages alike`() {
        val past = VmTextFormat.RELATIVE_WINDOW_DAYS * VmTextFormat.MILLIS_PER_DAY
        assertNull(VmTextFormat.relative(past))
        VmLocale.current = VmLocale.Fa
        assertNull(VmTextFormat.relative(past))
    }

    @Test
    fun `switching back to persian restores persian output`() {
        assertEquals("42", VmTextFormat.digits("42"))
        VmLocale.current = VmLocale.Fa
        assertEquals("۴۲", VmTextFormat.digits("42"))
    }

    @Test
    fun `an unknown language tag reads as persian rather than as half a translation`() {
        assertEquals(VmLocale.Fa, VmLocale.of(null))
        assertEquals(VmLocale.Fa, VmLocale.of(""))
        assertEquals(VmLocale.Fa, VmLocale.of("de-DE"))
        assertEquals(VmLocale.En, VmLocale.of("en"))
        assertEquals(VmLocale.En, VmLocale.of("en-GB"))
        assertEquals(VmLocale.En, VmLocale.of("EN"))
    }

    @Test
    fun `layout direction follows the language`() {
        assertTrue(VmLocale.Fa.isRtl)
        assertTrue(!VmLocale.En.isRtl)
    }
}
