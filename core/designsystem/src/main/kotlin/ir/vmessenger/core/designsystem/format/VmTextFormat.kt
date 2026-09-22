package ir.vmessenger.core.designsystem.format

import ir.vmessenger.core.common.text.BidiText
import ir.vmessenger.core.common.text.VmLocale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The pure, Android-free half of [VmDateFormat]: digits, byte sizes, durations and relative ages,
 * in whichever language the app is presenting itself in.
 *
 * It is deliberately free of `android.icu` so it can be covered by plain JVM unit tests (the
 * project runs no Robolectric), which is also why the locale arrives through
 * [VmLocale.current] rather than from an Android configuration.
 */
@Suppress("TooManyFunctions") // a catalogue of formatters, one per thing the UI renders
object VmTextFormat {

    private const val PERSIAN_ZERO = '۰'

    private const val BYTES_PER_KIB = 1024L
    private const val BYTES_PER_MIB = BYTES_PER_KIB * 1024L
    private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024L

    const val MILLIS_PER_SECOND = 1_000L
    const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
    const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
    const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR

    /** Beyond this age a timestamp is shown as a date instead of a relative phrase. */
    const val RELATIVE_WINDOW_DAYS = 7

    private const val PERCENT_SCALE = 100
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60

    /**
     * Wraps peer-supplied text — a name, a filename, a preview — so it cannot flip the sentence it
     * is substituted into. Delegates to [BidiText]; see there for why. It matters in both
     * languages: a Persian name inside an English sentence reorders it just as readily.
     */
    fun isolate(value: String): String = BidiText.isolate(value)

    /**
     * Digits in the app's language: Extended Arabic-Indic for Persian, ASCII untouched for English.
     * Other characters pass through either way.
     */
    fun digits(value: String): String {
        if (VmLocale.current == VmLocale.En) return value
        return buildString(value.length) {
            for (char in value) {
                if (char in '0'..'9') append(PERSIAN_ZERO + (char - '0')) else append(char)
            }
        }
    }

    /** `۱٫۲ مگابایت` / `1.2 MB`. Negative input is treated as zero. */
    fun fileSize(bytes: Long): String {
        val safe = if (bytes < 0) 0L else bytes
        val units = unitNames()
        return when {
            safe >= BYTES_PER_GIB -> "${decimal(safe, BYTES_PER_GIB)} ${units.gib}"
            safe >= BYTES_PER_MIB -> "${decimal(safe, BYTES_PER_MIB)} ${units.mib}"
            safe >= BYTES_PER_KIB -> "${digits((safe / BYTES_PER_KIB).toString())} ${units.kib}"
            else -> "${digits(safe.toString())} ${units.byte}"
        }
    }

    /** `۴۲٪` / `42%` — a 0..1 fraction as a whole percentage, clamped; used by progress readouts. */
    fun percent(fraction: Float): String {
        val clamped = fraction.coerceIn(0f, 1f)
        val sign = if (VmLocale.current == VmLocale.En) "%" else "٪"
        return digits(((clamped * PERCENT_SCALE).toInt()).toString()) + sign
    }

    /** `۰:۴۲` / `0:42`, `۱:۰۲:۰۳` / `1:02:03`. Used for voice messages and video length. */
    fun duration(ms: Long): String {
        val totalSeconds = (if (ms < 0) 0L else ms) / MILLIS_PER_SECOND
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        val raw = if (hours > 0) {
            "$hours:${pad(minutes)}:${pad(seconds)}"
        } else {
            "$minutes:${pad(seconds)}"
        }
        return digits(raw)
    }

    /**
     * `هم‌اکنون` / `just now`, `۲ دقیقه پیش` / `2 minutes ago`, or `null` once [ageMs] passes
     * [RELATIVE_WINDOW_DAYS] so the caller can fall back to a calendar date.
     */
    fun relative(ageMs: Long): String? {
        val age = abs(ageMs)
        return when {
            age < MILLIS_PER_MINUTE -> if (VmLocale.current == VmLocale.En) "just now" else "هم‌اکنون"
            age < MILLIS_PER_HOUR -> ago(age / MILLIS_PER_MINUTE, Unit.Minute)
            age < MILLIS_PER_DAY -> ago(age / MILLIS_PER_HOUR, Unit.Hour)
            age < RELATIVE_WINDOW_DAYS * MILLIS_PER_DAY -> ago(age / MILLIS_PER_DAY, Unit.Day)
            else -> null
        }
    }

    private enum class Unit { Minute, Hour, Day }

    /**
     * English pluralises on the number; Persian does not. Handled here rather than through
     * `<plurals>` because these strings have no resource context to resolve against — the same
     * reason the whole of this object is Android-free.
     */
    private fun ago(amount: Long, unit: Unit): String = if (VmLocale.current == VmLocale.En) {
        val noun = when (unit) {
            Unit.Minute -> "minute"
            Unit.Hour -> "hour"
            Unit.Day -> "day"
        }
        "$amount $noun${if (amount == 1L) "" else "s"} ago"
    } else {
        val noun = when (unit) {
            Unit.Minute -> "دقیقه"
            Unit.Hour -> "ساعت"
            Unit.Day -> "روز"
        }
        "${digits(amount.toString())} $noun پیش"
    }

    private class UnitNames(val byte: String, val kib: String, val mib: String, val gib: String)

    private fun unitNames(): UnitNames = if (VmLocale.current == VmLocale.En) {
        UnitNames("B", "KB", "MB", "GB")
    } else {
        UnitNames("بایت", "کیلوبایت", "مگابایت", "گیگابایت")
    }

    private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()

    /** One decimal place, in the locale's separator, with a bare integer when it rounds to zero. */
    private fun decimal(bytes: Long, unit: Long): String {
        val separator = if (VmLocale.current == VmLocale.En) '.' else '٫'
        val tenths = (bytes.toDouble() / unit * 10).roundToLong()
        val whole = tenths / 10
        val fraction = tenths % 10
        val raw = if (fraction == 0L) whole.toString() else "$whole$separator$fraction"
        return digits(raw)
    }
}
