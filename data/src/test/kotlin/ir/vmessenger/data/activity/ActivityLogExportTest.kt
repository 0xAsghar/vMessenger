package ir.vmessenger.data.activity

import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.database.entity.ActivityLogEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityLogExportTest {
    @Test
    fun `json escapes a detail that would otherwise break the document`() {
        val rendered = ActivityLogExport.render(
            listOf(entry(ActivityKind.NodeAdded, "a\"b\\c\nd")),
            ActivityLogFormat.Json,
        )

        assertTrue(rendered.contains("""\"b\\c\nd"""), rendered)
        // One line per entry plus the brackets; a raw newline in a detail would add another.
        assertEquals(3, rendered.lines().size, rendered)
    }

    @Test
    fun `a null detail is json null rather than the string null`() {
        val rendered = ActivityLogExport.render(listOf(entry(ActivityKind.AppLocked, null)), ActivityLogFormat.Json)

        assertTrue(rendered.contains(""""detail":null"""), rendered)
        assertFalse(rendered.contains(""""detail":"null""""), rendered)
    }

    @Test
    fun `csv quotes a detail containing its separator`() {
        val rendered = ActivityLogExport.render(
            listOf(entry(ActivityKind.NodeAdded, "host:1,2")),
            ActivityLogFormat.Csv,
        )

        assertTrue(rendered.endsWith(""""host:1,2""""), rendered)
    }

    @Test
    fun `csv neutralises a detail a spreadsheet would run as a formula`() {
        // A log entry is data. Nothing in it should be able to execute in the thing that opens it.
        val rendered = ActivityLogExport.render(
            listOf(entry(ActivityKind.Failure, "=1+1")),
            ActivityLogFormat.Csv,
        )

        assertTrue(rendered.trim().endsWith("'=1+1"), rendered)
    }

    @Test
    fun `csv carries a header so a column is never guessed`() {
        val rendered = ActivityLogExport.render(emptyList(), ActivityLogFormat.Csv)

        assertEquals("at,kind,detail\n", rendered)
    }

    @Test
    fun `text is one line per entry, oldest formatting rules aside`() {
        val rendered = ActivityLogExport.render(
            listOf(entry(ActivityKind.AppUnlocked, null), entry(ActivityKind.CallPlaced, null)),
            ActivityLogFormat.Text,
        )

        assertEquals(listOf("7 AppUnlocked", "7 CallPlaced"), rendered.lines())
    }

    @Test
    fun `every format renders an empty log without failing`() {
        ActivityLogFormat.entries.forEach { format ->
            ActivityLogExport.render(emptyList(), format)
        }
    }

    private fun entry(kind: ActivityKind, detail: String?) =
        ActivityLogEntity(kind = kind, detail = detail, atUnixMs = 7)
}
