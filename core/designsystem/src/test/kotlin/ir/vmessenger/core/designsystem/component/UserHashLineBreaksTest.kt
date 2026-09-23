package ir.vmessenger.core.designsystem.component

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class UserHashLineBreaksTest {

    private val raw = "vm2-AB-C"
    private val shown = UserHashLineBreaks.filter(AnnotatedString(raw))

    @Test
    fun `a break follows every dash and nothing else`() {
        assertEquals("vm2-​AB-​C", shown.text.text)
    }

    @Test
    fun `every offset in the entered text survives the round trip`() {
        for (offset in 0..raw.length) {
            val there = shown.offsetMapping.originalToTransformed(offset)
            assertEquals(offset, shown.offsetMapping.transformedToOriginal(there))
        }
    }

    @Test
    fun `a caret either side of a break sits just after its dash`() {
        assertEquals(4, shown.offsetMapping.transformedToOriginal(4))
        assertEquals(4, shown.offsetMapping.transformedToOriginal(5))
    }

    @Test
    fun `an ID ending in a dash still maps its end`() {
        val trailing = UserHashLineBreaks.filter(AnnotatedString("vm2-"))
        assertEquals(4, trailing.offsetMapping.transformedToOriginal(trailing.text.length))
        assertEquals(trailing.text.length, trailing.offsetMapping.originalToTransformed(4))
    }
}
