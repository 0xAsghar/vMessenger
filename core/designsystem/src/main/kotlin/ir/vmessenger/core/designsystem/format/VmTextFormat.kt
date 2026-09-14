package ir.vmessenger.core.designsystem.format

import ir.vmessenger.core.common.text.BidiText
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The pure, Android-free half of [VmDateFormat]: Persian digits, byte sizes, durations and
 * relative ages. It is deliberately free of `android.icu` so it can be covered by plain JVM
 * unit tests (the project runs no Robolectric).
 */
object VmTextFormat {

    private const val PERSIAN_ZERO = '۰'
    private const val DECIMAL_SEPARATOR = '٫'

    private const val BYTES_PER_KIB = 1024L
    private const val BYTES_PER_MIB = BYTES_PER_KIB * 1024L
    private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024L

    private const val UNIT_BYTE = "بایت"
    private const val UNIT_KIB = "کیلوبایت"
    private const val UNIT_MIB = "مگابایت"
    private const val UNIT_GIB = "گیگابایت"

    private const val JUST_NOW = "هم‌اکنون"
    private const val MINUTES_AGO = "دقیقه پیش"
    private const val HOURS_AGO = "ساعت پیش"
    private const val DAYS_AGO = "روز پیش"

    const val MILLIS_PER_SECOND = 1_000L
    const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
    const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
    const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR

    /** Beyond this age a timestamp is shown as a Jalali date instead of a relative phrase. */
    const val RELATIVE_WINDOW_DAYS = 7

    private const val PERCENT_SCALE = 100
    private const val PERCENT_SIGN = "٪"
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60

    /**
     * Wraps peer-supplied text — a name, a filename, a preview — so it cannot flip the Persian
     * sentence it is substituted into. Delegates to [BidiText]; see there for why.
     */
    fun isolate(value: String): String = BidiText.isolate(value)

    /** Rewrites ASCII digits as Extended Arabic-Indic (Persian) digits; other characters pass through. */
    fun persianDigits(value: String): String = buildString(value.length) {
        for (char in value) {
            if (char in '0'..'9') append(PERSIAN_ZERO + (char - '0')) else append(char)
        }
    }

    /** `۱٫۲ مگابایت`, `۳۴۰ کیلوبایت`, `۹۵۰ بایت`. Negative input is treated as zero. */
    fun fileSize(bytes: Long): String {
        val safe = if (bytes < 0) 0L else bytes
        return when {
            safe >= BYTES_PER_GIB -> "${decimal(safe, BYTES_PER_GIB)} $UNIT_GIB"
            safe >= BYTES_PER_MIB -> "${decimal(safe, BYTES_PER_MIB)} $UNIT_MIB"
            safe >= BYTES_PER_KIB -> "${persianDigits((safe / BYTES_PER_KIB).toString())} $UNIT_KIB"
            else -> "${persianDigits(safe.toString())} $UNIT_BYTE"
        }
    }

    /** `۴۲٪` — a 0..1 fraction as a whole percentage, clamped; used by progress readouts. */
    fun percent(fraction: Float): String {
        val clamped = fraction.coerceIn(0f, 1f)
        return persianDigits(((clamped * PERCENT_SCALE).toInt()).toString()) + PERCENT_SIGN
    }

    /** `۰:۴۲`, `۱۲:۰۵`, `۱:۰۲:۰۳`. Used for voice messages and video length. */
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
        return persianDigits(raw)
    }

    /**
     * `هم‌اکنون` / `۲ دقیقه پیش` / `۳ ساعت پیش` / `۵ روز پیش`, or `null` once [ageMs] passes
     * [RELATIVE_WINDOW_DAYS] so the caller can fall back to a Jalali date.
     */
    fun relative(ageMs: Long): String? {
        val age = abs(ageMs)
        return when {
            age < MILLIS_PER_MINUTE -> JUST_NOW
            age < MILLIS_PER_HOUR -> unit(age / MILLIS_PER_MINUTE, MINUTES_AGO)
            age < MILLIS_PER_DAY -> unit(age / MILLIS_PER_HOUR, HOURS_AGO)
            age < RELATIVE_WINDOW_DAYS * MILLIS_PER_DAY -> unit(age / MILLIS_PER_DAY, DAYS_AGO)
            else -> null
        }
    }

    private fun unit(amount: Long, suffix: String): String = "${persianDigits(amount.toString())} $suffix"

    private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()

    /** One decimal place, Persian separator, with a bare integer when the fraction rounds to zero. */
    private fun decimal(bytes: Long, unit: Long): String {
        val tenths = (bytes.toDouble() / unit * 10).roundToLong()
        val whole = tenths / 10
        val fraction = tenths % 10
        val raw = if (fraction == 0L) whole.toString() else "$whole$DECIMAL_SEPARATOR$fraction"
        return persianDigits(raw)
    }
}
