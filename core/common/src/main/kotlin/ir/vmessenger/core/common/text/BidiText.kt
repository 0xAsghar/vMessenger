package ir.vmessenger.core.common.text

/**
 * Directional isolates for text the app did not write.
 *
 * The UI is a single Persian, right-to-left locale, and every text style resolves its paragraph
 * direction from its first strong character. That is right for a message body — a Latin message
 * should lay out left-to-right — but wrong for a Persian sentence with a peer-supplied name
 * substituted into it: «Ali به گروه اضافه شد» takes Ali's direction and flips the whole line,
 * stranding the punctuation on the wrong side. Wrapping just the name makes that run opaque to the
 * paragraph around it while still laying the name out correctly on its own.
 *
 * Lives in `:core:common` rather than beside the other formatters because the three callers sit in
 * different modules — the UI, the persisted group system lines, and the notification shade — and
 * the codepoints should be written down exactly once.
 */
object BidiText {
    private const val FIRST_STRONG_ISOLATE = '⁨'
    private const val POP_DIRECTIONAL_ISOLATE = '⁩'

    /** Wraps [value] so it cannot influence the direction of the text around it. Empty stays empty. */
    fun isolate(value: String): String =
        if (value.isEmpty()) value else "$FIRST_STRONG_ISOLATE$value$POP_DIRECTIONAL_ISOLATE"
}
