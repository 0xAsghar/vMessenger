package ir.vmessenger.core.designsystem.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the Android-free half of the formatter. The ICU/Jalali half lives in [VmDateFormat]
 * and needs a device, so it is exercised by the two-emulator matrix instead.
 */
class VmTextFormatTest {

    @Test
    fun `persian digits replace ascii digits only`() {
        assertEquals("۱۲۳۴۵۶۷۸۹۰", VmTextFormat.persianDigits("1234567890"))
        assertEquals("نسخهٔ ۱٫۰٫۰ (۴۵)", VmTextFormat.persianDigits("نسخهٔ 1٫0٫0 (45)"))
        assertEquals("", VmTextFormat.persianDigits(""))
    }

    @Test
    fun `persian digits are idempotent`() {
        val once = VmTextFormat.persianDigits("42")
        assertEquals(once, VmTextFormat.persianDigits(once))
    }

    @Test
    fun `file size uses persian units and digits`() {
        assertEquals("۰ بایت", VmTextFormat.fileSize(0))
        assertEquals("۹۵۰ بایت", VmTextFormat.fileSize(950))
        assertEquals("۱ کیلوبایت", VmTextFormat.fileSize(1024))
        assertEquals("۳۴۰ کیلوبایت", VmTextFormat.fileSize(340 * 1024))
        assertEquals("۱٫۲ مگابایت", VmTextFormat.fileSize((1.2 * 1024 * 1024).toLong()))
        assertEquals("۲ مگابایت", VmTextFormat.fileSize(2L * 1024 * 1024))
        assertEquals("۱٫۵ گیگابایت", VmTextFormat.fileSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `negative file size is clamped to zero`() {
        assertEquals("۰ بایت", VmTextFormat.fileSize(-1))
    }

    @Test
    fun `duration pads seconds and adds hours only when needed`() {
        assertEquals("۰:۰۰", VmTextFormat.duration(0))
        assertEquals("۰:۴۲", VmTextFormat.duration(42_000))
        assertEquals("۱۲:۰۵", VmTextFormat.duration(12 * 60_000 + 5_000L))
        assertEquals("۱:۰۲:۰۳", VmTextFormat.duration(3_600_000 + 2 * 60_000 + 3_000L))
    }

    @Test
    fun `duration ignores sub-second remainder and negative input`() {
        assertEquals("۰:۰۱", VmTextFormat.duration(1_999))
        assertEquals("۰:۰۰", VmTextFormat.duration(-5_000))
    }

    @Test
    fun `relative covers seconds minutes hours and days`() {
        assertEquals("هم‌اکنون", VmTextFormat.relative(0))
        assertEquals("هم‌اکنون", VmTextFormat.relative(59_000))
        assertEquals("۲ دقیقه پیش", VmTextFormat.relative(2 * VmTextFormat.MILLIS_PER_MINUTE))
        assertEquals("۳ ساعت پیش", VmTextFormat.relative(3 * VmTextFormat.MILLIS_PER_HOUR))
        assertEquals("۵ روز پیش", VmTextFormat.relative(5 * VmTextFormat.MILLIS_PER_DAY))
    }

    @Test
    fun `relative returns null past the seven day window`() {
        assertNull(VmTextFormat.relative(VmTextFormat.RELATIVE_WINDOW_DAYS * VmTextFormat.MILLIS_PER_DAY))
        assertNull(VmTextFormat.relative(30 * VmTextFormat.MILLIS_PER_DAY))
    }

    @Test
    fun `relative treats a clock skewed future timestamp as its absolute age`() {
        assertEquals("۲ دقیقه پیش", VmTextFormat.relative(-2 * VmTextFormat.MILLIS_PER_MINUTE))
    }
}
