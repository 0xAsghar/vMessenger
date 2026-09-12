package ir.vmessenger.core.designsystem.format

import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.ULocale
import java.util.Date

/**
 * Jalali (Persian) date and time formatting for the whole app.
 *
 * Everything goes through `ULocale("fa_IR@calendar=persian")`, so ICU supplies both the
 * Persian calendar and Extended Arabic-Indic digits; [VmTextFormat] supplies the parts that
 * have no ICU equivalent (byte sizes, durations, relative ages) and is unit-tested directly.
 */
object VmDateFormat {

    private const val TODAY = "امروز"
    private const val YESTERDAY = "دیروز"

    private const val PATTERN_TIME = "HH:mm"
    private const val PATTERN_WEEKDAY = "EEEE"
    private const val PATTERN_DAY_MONTH = "d MMMM"
    private const val PATTERN_DAY_MONTH_YEAR = "d MMMM y"
    private const val PATTERN_SHORT_DATE = "d MMM"
    private const val PATTERN_SHORT_DATE_YEAR = "d MMM y"

    private val persianLocale = ULocale("fa_IR@calendar=persian")

    /** `SimpleDateFormat` is not thread-safe, so each thread keeps its own pattern cache. */
    private val formatters = ThreadLocal.withInitial { mutableMapOf<String, SimpleDateFormat>() }

    /** `۱۴:۰۵` — wall-clock time, used inside bubbles. */
    fun time(ms: Long): String = format(PATTERN_TIME, ms)

    /** `امروز` / `دیروز` / weekday within a week / `۱۲ شهریور` / `۱۲ شهریور ۱۴۰۳`. */
    fun daySeparator(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val days = dayDelta(nowMs, ms)
        return when {
            days == 0 -> TODAY
            days == 1 -> YESTERDAY
            days in 2 until VmTextFormat.RELATIVE_WINDOW_DAYS -> format(PATTERN_WEEKDAY, ms)
            sameYear(nowMs, ms) -> format(PATTERN_DAY_MONTH, ms)
            else -> format(PATTERN_DAY_MONTH_YEAR, ms)
        }
    }

    /** Trailing timestamp of a chat-list row: time today, then `دیروز`, weekday, short date. */
    fun chatListTime(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val days = dayDelta(nowMs, ms)
        return when {
            days == 0 -> format(PATTERN_TIME, ms)
            days == 1 -> YESTERDAY
            days in 2 until VmTextFormat.RELATIVE_WINDOW_DAYS -> format(PATTERN_WEEKDAY, ms)
            sameYear(nowMs, ms) -> format(PATTERN_SHORT_DATE, ms)
            else -> format(PATTERN_SHORT_DATE_YEAR, ms)
        }
    }

    /** `۱٫۲ مگابایت` — see [VmTextFormat.fileSize]. */
    fun fileSize(bytes: Long): String = VmTextFormat.fileSize(bytes)

    /** `۰:۴۲` — see [VmTextFormat.duration]. */
    fun duration(ms: Long): String = VmTextFormat.duration(ms)

    /** `۲ دقیقه پیش`, falling back to a Jalali date once the timestamp leaves the relative window. */
    fun relative(ms: Long, nowMs: Long = System.currentTimeMillis()): String =
        VmTextFormat.relative(nowMs - ms) ?: daySeparator(ms, nowMs)

    private fun format(pattern: String, ms: Long): String {
        val formatter = formatters.get()?.getOrPut(pattern) { SimpleDateFormat(pattern, persianLocale) }
            ?: SimpleDateFormat(pattern, persianLocale)
        return VmTextFormat.persianDigits(formatter.format(Date(ms)))
    }

    /** Whole calendar days between two instants, in the device time zone. */
    private fun dayDelta(nowMs: Long, thenMs: Long): Int =
        calendar(nowMs).get(Calendar.JULIAN_DAY) - calendar(thenMs).get(Calendar.JULIAN_DAY)

    private fun sameYear(nowMs: Long, thenMs: Long): Boolean =
        calendar(nowMs).get(Calendar.YEAR) == calendar(thenMs).get(Calendar.YEAR)

    private fun calendar(ms: Long): Calendar =
        Calendar.getInstance(persianLocale).apply { timeInMillis = ms }
}
