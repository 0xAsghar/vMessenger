package ir.vmessenger.core.designsystem.format

import android.icu.text.DateFormatSymbols
import android.icu.util.Calendar
import androidx.compose.runtime.Immutable

/**
 * Day arithmetic in the app's calendar: Jalali in Persian, Gregorian in English — the same choice
 * [VmDateFormat] makes for display, so the grid a user picks a day from and the date they then read
 * back always agree. ICU does the calendar; this only asks it the questions a month grid needs.
 *
 * Every instant here is in the device time zone, and every "day" is that day's first millisecond.
 */
object VmCalendar {

    /** One month, as a seven-column grid starting on the locale's first weekday lays it out. */
    @Immutable
    data class Month(
        /** Midnight on the first of the month. */
        val firstDayMs: Long,
        /** Empty cells before the 1st: how far the 1st falls from the start of the week. */
        val leadingBlanks: Int,
        val dayCount: Int,
    )

    /** The month [ms] falls in. */
    fun monthOf(ms: Long): Month {
        val calendar = startOfDay(ms).apply { set(Calendar.DAY_OF_MONTH, 1) }
        return Month(
            firstDayMs = calendar.timeInMillis,
            leadingBlanks = Math.floorMod(calendar.get(Calendar.DAY_OF_WEEK) - calendar.firstDayOfWeek, DAYS_PER_WEEK),
            dayCount = calendar.getActualMaximum(Calendar.DAY_OF_MONTH),
        )
    }

    /** The month [delta] months after (or, negative, before) [month]. */
    fun shift(month: Month, delta: Int): Month =
        monthOf(startOfDay(month.firstDayMs).apply { add(Calendar.MONTH, delta) }.timeInMillis)

    /** Midnight on day [day] (1-based) of [month]. */
    fun day(month: Month, day: Int): Long =
        startOfDay(month.firstDayMs).apply { set(Calendar.DAY_OF_MONTH, day) }.timeInMillis

    /** Midnight on the day [ms] falls in. */
    fun dayOf(ms: Long): Long = startOfDay(ms).timeInMillis

    /** [hour]:[minute] on the day [dayMs] falls in; through the calendar, so a DST change is honoured. */
    fun atTime(dayMs: Long, hour: Int, minute: Int): Long = startOfDay(dayMs).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }.timeInMillis

    fun hourOf(ms: Long): Int = calendar(ms).get(Calendar.HOUR_OF_DAY)

    fun minuteOf(ms: Long): Int = calendar(ms).get(Calendar.MINUTE)

    /** One-letter weekday names in grid order, starting from the locale's first day of the week. */
    fun weekdayInitials(): List<String> {
        val first = calendar(System.currentTimeMillis()).firstDayOfWeek
        // Indexed by Calendar.SUNDAY (1) … Calendar.SATURDAY (7); index 0 is unused.
        val names = DateFormatSymbols.getInstance(VmDateFormat.locale())
            .getWeekdays(DateFormatSymbols.STANDALONE, DateFormatSymbols.NARROW)
        return List(DAYS_PER_WEEK) { offset -> names[(first - 1 + offset) % DAYS_PER_WEEK + 1] }
    }

    private fun calendar(ms: Long): Calendar =
        Calendar.getInstance(VmDateFormat.locale()).apply { timeInMillis = ms }

    private fun startOfDay(ms: Long): Calendar = calendar(ms).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private const val DAYS_PER_WEEK = 7
}
