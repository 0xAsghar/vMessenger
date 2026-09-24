package ir.vmessenger.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumRowsTest {
    @Test
    fun `small albums are a pair, a wide photo over a pair, and two pairs`() {
        assertEquals(listOf(2), albumRows(2))
        assertEquals(listOf(1, 2), albumRows(3))
        assertEquals(listOf(2, 2), albumRows(4))
    }

    @Test
    fun `larger albums are rows of three with the remainder on top`() {
        assertEquals(listOf(2, 3), albumRows(5))
        assertEquals(listOf(3, 3), albumRows(6))
        assertEquals(listOf(1, 3, 3), albumRows(7))
        assertEquals(listOf(1, 3, 3, 3), albumRows(10))
    }

    @Test
    fun `every photo gets exactly one tile`() {
        for (count in 1..MAX_PICKED) {
            assertEquals(count, albumRows(count).sum())
        }
    }

    private companion object {
        const val MAX_PICKED = 30
    }
}
