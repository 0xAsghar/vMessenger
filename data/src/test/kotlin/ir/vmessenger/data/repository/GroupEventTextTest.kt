package ir.vmessenger.data.repository

import ir.vmessenger.core.common.text.BidiText
import ir.vmessenger.core.common.text.VmLocale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A group's history line is written once, in the language the app is using at that moment.
 *
 * [VmLocale.current] is process-global, so every test here puts it back.
 */
class GroupEventTextTest {

    @After
    fun tearDown() {
        VmLocale.current = VmLocale.Fa
    }

    @Test
    fun `lines are written in english when the app is in english`() {
        VmLocale.current = VmLocale.En
        assertEquals("${BidiText.isolate("Ali")} left the group", GroupEventText.left("Ali"))
        assertEquals("You were removed from the group", GroupEventText.REMOVED_ME)
    }

    @Test
    fun `lines are written in persian otherwise, the name still isolated`() {
        VmLocale.current = VmLocale.Fa
        assertEquals("${BidiText.isolate("Ali")} گروه را ترک کرد", GroupEventText.left("Ali"))
        assertEquals("شما از گروه حذف شدید", GroupEventText.REMOVED_ME)
    }
}
