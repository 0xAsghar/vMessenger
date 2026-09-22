package ir.vmessenger.core.common.text

/**
 * Which language the app is presenting itself in.
 *
 * A global rather than a parameter threaded through every formatter, and deliberately so. The
 * alternative was an argument on all ~80 call sites that format a number or a date, and a formatter
 * you have to *remember* to hand the locale to is a formatter that will be called with the wrong
 * one somewhere — most likely in a notification built off the main thread, which is exactly where a
 * mismatch is hardest to notice. Same shape as [ir.vmessenger.core.common.network.NodeAddressPolicy]
 * elsewhere in this codebase: one settable value, read where it is needed.
 *
 * Set from the app locale at start-up and whenever the user changes it. Changing the app locale
 * recreates the activity, so composition re-reads it without anything observing it.
 */
enum class VmLocale(val tag: String) {
    Fa("fa"),
    En("en"),
    ;

    /** Persian is written right to left; English is not. The layout direction follows this. */
    val isRtl: Boolean get() = this == Fa

    companion object {
        /**
         * Persian by default, which is what every install before V2 had and what an install with no
         * stored preference should keep. Volatile because notification and worker threads read it.
         */
        @Volatile
        var current: VmLocale = Fa

        /**
         * Resolves a BCP-47 tag, however partial. Anything that is not recognisably English reads
         * as Persian: a tag the app does not know should fall back to the language it was written
         * in, not to a half-translated one.
         */
        fun of(tag: String?): VmLocale =
            if (tag?.lowercase()?.startsWith("en") == true) En else Fa
    }
}
