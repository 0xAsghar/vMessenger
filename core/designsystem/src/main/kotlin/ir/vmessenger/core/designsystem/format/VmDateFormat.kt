package ir.vmessenger.core.designsystem.format

import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.ULocale
import ir.vmessenger.core.common.text.VmLocale
import java.util.Date

/**
 * Date and time formatting for the whole app, in whichever language it is presenting itself in.
 *
 * Persian goes through `ULocale("fa_IR@calendar=persian")`, so ICU supplies both the Jalali
 * calendar and Extended Arabic-Indic digits; English goes through `en_US` and its Gregorian
 * calendar. The calendar follows the language rather than the device, because a Jalali date in an
 * otherwise English screen is not a translation of anything.
 *
 * [VmTextFormat] supplies the parts that have no ICU equivalent (byte sizes, durations, relative
 * ages) and is unit-tested directly.
 */
@Suppress("TooManyFunctions") // one function per place a date or size is rendered; a catalogue, not a class
object VmDateFormat {

    private const val TODAY_FA = "امروز"
    private const val YESTERDAY_FA = "دیروز"
    private const val TODAY_EN = "Today"
    private const val YESTERDAY_EN = "Yesterday"

    private const val PATTERN_TIME = "HH:mm"
    private const val PATTERN_WEEKDAY = "EEEE"
    private const val PATTERN_DAY_MONTH = "d MMMM"
    private const val PATTERN_DAY_MONTH_YEAR = "d MMMM y"
    private const val PATTERN_SHORT_DATE = "d MMM"
    private const val PATTERN_SHORT_DATE_YEAR = "d MMM y"
    private const val PATTERN_MONTH_YEAR = "MMMM y"
    private const val PATTERN_FULL_DATE = "EEEE d MMMM y"

    private const val DAY_TIME_SEPARATOR_FA = "، "
    private const val DAY_TIME_SEPARATOR_EN = ", "

    private val persianLocale = ULocale("fa_IR@calendar=persian")
    private val englishLocale = ULocale("en_US")

    /** The ICU locale for the app's language; carries the calendar as well as the digits. */
    internal fun locale(): ULocale =
        if (VmLocale.current == VmLocale.En) englishLocale else persianLocale

    private fun today(): String = if (VmLocale.current == VmLocale.En) TODAY_EN else TODAY_FA

    private fun yesterday(): String =
        if (VmLocale.current == VmLocale.En) YESTERDAY_EN else YESTERDAY_FA

    /** `SimpleDateFormat` is not thread-safe, so each thread keeps its own pattern cache. */
    private val formatters = ThreadLocal.withInitial { mutableMapOf<String, SimpleDateFormat>() }

    /** `۱۴:۰۵` — wall-clock time, used inside bubbles. */
    fun time(ms: Long): String = format(PATTERN_TIME, ms)

    /** `امروز` / `دیروز` / weekday within a week / `۱۲ شهریور` / `۱۲ شهریور ۱۴۰۳`. */
    fun daySeparator(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val days = dayDelta(nowMs, ms)
        return when {
            days == 0 -> today()
            days == 1 -> yesterday()
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
            days == 1 -> yesterday()
            days in 2 until VmTextFormat.RELATIVE_WINDOW_DAYS -> format(PATTERN_WEEKDAY, ms)
            sameYear(nowMs, ms) -> format(PATTERN_SHORT_DATE, ms)
            else -> format(PATTERN_SHORT_DATE_YEAR, ms)
        }
    }

    /** `۱۲ شهریور، ۱۴:۰۵` — a moment that is not today, spelled out; used for "last checked". */
    fun dayAndTime(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val separator = if (VmLocale.current == VmLocale.En) DAY_TIME_SEPARATOR_EN else DAY_TIME_SEPARATOR_FA
        return "${daySeparator(ms, nowMs)}$separator${format(PATTERN_TIME, ms)}"
    }

    /** `مهر ۱۴۰۵` / `October 2026` — the heading of a month in a date picker. */
    fun monthAndYear(ms: Long): String = format(PATTERN_MONTH_YEAR, ms)

    /** `شنبه ۱۲ مهر ۱۴۰۵، ۱۴:۰۵` — one moment in full, with nothing left to infer. */
    fun fullDateAndTime(ms: Long): String {
        val separator = if (VmLocale.current == VmLocale.En) DAY_TIME_SEPARATOR_EN else DAY_TIME_SEPARATOR_FA
        return "${format(PATTERN_FULL_DATE, ms)}$separator${format(PATTERN_TIME, ms)}"
    }

    /** `شنبه ۱۲ مهر ۱۴۰۵` — a day in full; what a screen reader says for a day in a date picker. */
    fun fullDate(ms: Long): String = format(PATTERN_FULL_DATE, ms)

    /** `۱۲` — a number as the app writes it, for a day in a calendar grid or a field of a time. */
    fun number(value: Int, minDigits: Int = 1): String = VmTextFormat.digits(value.toString().padStart(minDigits, '0'))

    /** `۱٫۲ مگابایت` — see [VmTextFormat.fileSize]. */
    fun fileSize(bytes: Long): String = VmTextFormat.fileSize(bytes)

    /** `۰:۴۲` — see [VmTextFormat.duration]. */
    fun duration(ms: Long): String = VmTextFormat.duration(ms)

    /** `۲ دقیقه پیش`, falling back to a Jalali date once the timestamp leaves the relative window. */
    fun relative(ms: Long, nowMs: Long = System.currentTimeMillis()): String =
        VmTextFormat.relative(nowMs - ms) ?: daySeparator(ms, nowMs)

    /**
     * The cache key carries the locale as well as the pattern: the same pattern in two languages is
     * two different formatters, and sharing one entry between them would render whichever language
     * happened to be active when the thread first used it.
     */
    private fun format(pattern: String, ms: Long): String {
        val locale = locale()
        val key = "${locale.name}|$pattern"
        val formatter = formatters.get()?.getOrPut(key) { SimpleDateFormat(pattern, locale) }
            ?: SimpleDateFormat(pattern, locale)
        return VmTextFormat.digits(formatter.format(Date(ms)))
    }

    /** Whole calendar days between two instants, in the device time zone. */
    private fun dayDelta(nowMs: Long, thenMs: Long): Int =
        calendar(nowMs).get(Calendar.JULIAN_DAY) - calendar(thenMs).get(Calendar.JULIAN_DAY)

    private fun sameYear(nowMs: Long, thenMs: Long): Boolean =
        calendar(nowMs).get(Calendar.YEAR) == calendar(thenMs).get(Calendar.YEAR)

    private fun calendar(ms: Long): Calendar =
        Calendar.getInstance(locale()).apply { timeInMillis = ms }
}
