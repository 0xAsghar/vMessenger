package ir.vmessenger.data.activity

import ir.vmessenger.domain.model.ActivityEvent

/** The formats the user's own log can be exported in. */
enum class ActivityLogFormat(val extension: String, val mimeType: String) {
    Json("json", "application/json"),
    Csv("csv", "text/csv"),
    Text("txt", "text/plain"),
}

/**
 * Renders the activity log as text.
 *
 * Pure, so what leaves the device is testable. Timestamps are written as Unix milliseconds rather
 * than as formatted local dates: the export is a record, and a record that silently depends on the
 * device's timezone and calendar is one that cannot be compared with another device's.
 *
 * Nothing is added here that is not in the rows. If a detail is absent from a row it is absent from
 * the export — there is no enrichment step that could reach for a contact name or a message.
 */
object ActivityLogExport {
    fun render(entries: List<ActivityEvent>, format: ActivityLogFormat): String = when (format) {
        ActivityLogFormat.Json -> json(entries)
        ActivityLogFormat.Csv -> csv(entries)
        ActivityLogFormat.Text -> text(entries)
    }

    private fun json(entries: List<ActivityEvent>): String =
        entries.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { entry ->
            val detail = entry.detail?.let { "\"${escapeJson(it)}\"" } ?: "null"
            """  {"at":${entry.atUnixMs},"kind":"${entry.kind.name}","detail":$detail}"""
        }

    private fun csv(entries: List<ActivityEvent>): String =
        entries.joinToString(separator = "\n", prefix = "at,kind,detail\n") { entry ->
            "${entry.atUnixMs},${entry.kind.name},${escapeCsv(entry.detail.orEmpty())}"
        }

    private fun text(entries: List<ActivityEvent>): String =
        entries.joinToString(separator = "\n") { entry ->
            val detail = entry.detail?.let { " ($it)" }.orEmpty()
            "${entry.atUnixMs} ${entry.kind.name}$detail"
        }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /**
     * Quoted whenever a separator, a quote or a newline would otherwise break the row — and a
     * leading `=`, `+`, `-` or `@` is prefixed, because a spreadsheet reads those as a formula.
     * A log entry is data; it should not be able to execute in the thing that opens it.
     */
    private fun escapeCsv(value: String): String {
        val guarded = if (value.firstOrNull() in FORMULA_LEADS) "'$value" else value
        return if (guarded.any { it in CSV_SPECIALS }) {
            "\"${guarded.replace("\"", "\"\"")}\""
        } else {
            guarded
        }
    }

    private val FORMULA_LEADS = charArrayOf('=', '+', '-', '@').toList()
    private val CSV_SPECIALS = charArrayOf(',', '"', '\n', '\r').toList()
}
