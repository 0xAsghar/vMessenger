package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmCalendar
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A day and a time, picked in the app's own calendar — Jalali in Persian, Gregorian in English —
 * for anything that has to happen at a moment rather than after a while.
 *
 * Days outside [range] are drawn but cannot be chosen, and confirming stays off until the chosen
 * moment is inside it, so [onConfirm] never receives a time the caller did not allow. Minutes move
 * in steps of five: a deadline is a moment someone reads back, not a stopwatch.
 */
@Composable
@Suppress("LongParameterList") // a dialog: the words it shows, the window it allows, and the two ways out
fun VmDateTimePickerDialog(
    title: String,
    description: String?,
    confirmLabel: String,
    range: LongRange,
    initialMs: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var dayMs by rememberSaveable { mutableLongStateOf(VmCalendar.dayOf(initialMs)) }
    var hour by rememberSaveable { mutableIntStateOf(VmCalendar.hourOf(initialMs)) }
    var minute by rememberSaveable { mutableIntStateOf(VmCalendar.minuteOf(initialMs) / MINUTE_STEP * MINUTE_STEP) }
    var shownMonthMs by rememberSaveable { mutableLongStateOf(VmCalendar.monthOf(initialMs).firstDayMs) }
    val chosen = VmCalendar.atTime(dayMs, hour, minute)
    VmDialog(
        onDismissRequest = onDismiss,
        title = title,
        buttons = {
            VmTextButton(text = stringResource(R.string.vm_cancel), onClick = onDismiss)
            VmTextButton(text = confirmLabel, onClick = { onConfirm(chosen) }, enabled = chosen in range)
        },
    ) {
        if (description != null) VmText(text = description)
        val month = remember(shownMonthMs) { VmCalendar.monthOf(shownMonthMs) }
        MonthHeader(month = month, range = range, onShow = { shownMonthMs = it.firstDayMs })
        DayGrid(month = month, selectedDayMs = dayMs, range = range, onPick = { dayMs = it })
        TimeRow(
            hour = hour,
            minute = minute,
            onHour = { hour = Math.floorMod(hour + it, HOURS_PER_DAY) },
            onMinute = { minute = Math.floorMod(minute + it * MINUTE_STEP, MINUTES_PER_HOUR) },
        )
        ChosenMoment(chosen = chosen, range = range)
    }
}

@Composable
private fun MonthHeader(month: VmCalendar.Month, range: LongRange, onShow: (VmCalendar.Month) -> Unit) {
    val previous = remember(month) { VmCalendar.shift(month, -1) }
    val next = remember(month) { VmCalendar.shift(month, 1) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        VmIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.vm_picker_previous_month),
            onClick = { onShow(previous) },
            // A month is worth showing while any of it is still ahead of the earliest allowed moment.
            enabled = month.firstDayMs > VmCalendar.dayOf(range.first),
        )
        VmText(
            text = VmDateFormat.monthAndYear(month.firstDayMs),
            style = VmTheme.typography.bodyLgMedium,
            color = VmTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        VmIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.vm_picker_next_month),
            onClick = { onShow(next) },
            enabled = next.firstDayMs <= range.last,
        )
    }
}

/** Seven columns from the locale's first weekday; the reading direction orders them, as on paper. */
@Composable
private fun DayGrid(month: VmCalendar.Month, selectedDayMs: Long, range: LongRange, onPick: (Long) -> Unit) {
    val weekdays = remember { VmCalendar.weekdayInitials() }
    val days = remember(month) { (1..month.dayCount).map { VmCalendar.day(month, it) } }
    val today = remember { VmCalendar.dayOf(System.currentTimeMillis()) }
    val firstAllowedDay = remember(range) { VmCalendar.dayOf(range.first) }
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (name in weekdays) {
                VmText(
                    text = name,
                    style = VmTheme.typography.bodySm,
                    color = VmTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val rows = (month.leadingBlanks + month.dayCount + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until DAYS_PER_WEEK) {
                    val index = row * DAYS_PER_WEEK + column - month.leadingBlanks
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).height(CELL_HEIGHT)) {
                        days.getOrNull(index)?.let { dayMs ->
                            DayCell(
                                number = index + 1,
                                dayMs = dayMs,
                                state = DayState(
                                    selected = dayMs == selectedDayMs,
                                    today = dayMs == today,
                                    enabled = dayMs >= firstAllowedDay && dayMs <= range.last,
                                ),
                                onPick = onPick,
                            )
                        }
                    }
                }
            }
        }
    }
}

private class DayState(val selected: Boolean, val today: Boolean, val enabled: Boolean)

@Composable
private fun DayCell(number: Int, dayMs: Long, state: DayState, onPick: (Long) -> Unit) {
    val c = VmTheme.colors
    val content = when {
        state.selected -> c.textOnSolid
        !state.enabled -> c.textDisabled
        state.today -> c.textAccent
        else -> c.textPrimary
    }
    VmSurface(
        onClick = { onPick(dayMs) },
        enabled = state.enabled,
        shape = CircleShape,
        color = if (state.selected) c.bgAccent else Color.Transparent,
        contentColor = content,
        modifier = Modifier
            .size(DAY_SIZE)
            .semantics {
                contentDescription = VmDateFormat.fullDate(dayMs)
                selected = state.selected
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            VmText(
                text = VmDateFormat.number(number),
                style = if (state.today) VmTheme.typography.bodyMdMedium else VmTheme.typography.bodyMd,
                color = content,
            )
        }
    }
}

/** Hours then minutes, left to right in either language, the way a clock is written in both. */
@Composable
private fun TimeRow(hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TimeField(
                value = hour,
                label = stringResource(R.string.vm_picker_hour),
                later = stringResource(R.string.vm_picker_hour_later),
                earlier = stringResource(R.string.vm_picker_hour_earlier),
                onStep = onHour,
            )
            VmText(text = ":", style = VmTheme.typography.headingMd, color = VmTheme.colors.textPrimary)
            TimeField(
                value = minute,
                label = stringResource(R.string.vm_picker_minute),
                later = stringResource(R.string.vm_picker_minutes_later),
                earlier = stringResource(R.string.vm_picker_minutes_earlier),
                onStep = onMinute,
            )
        }
    }
}

@Composable
private fun TimeField(value: Int, label: String, later: String, earlier: String, onStep: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VmIconButton(icon = Icons.Filled.KeyboardArrowUp, contentDescription = later, onClick = { onStep(1) })
        VmText(
            text = VmDateFormat.number(value, minDigits = 2),
            style = VmTheme.typography.headingMd,
            color = VmTheme.colors.textPrimary,
            modifier = Modifier.semantics { contentDescription = "$label ${VmDateFormat.number(value)}" },
        )
        VmIconButton(icon = Icons.Filled.KeyboardArrowDown, contentDescription = earlier, onClick = { onStep(-1) })
    }
}

/** The whole moment in words, since the grid shows only a day number; or why it cannot be chosen. */
@Composable
private fun ChosenMoment(chosen: Long, range: LongRange) {
    val problem = when {
        chosen < range.first -> R.string.vm_picker_in_past
        chosen > range.last -> R.string.vm_picker_too_late
        else -> null
    }
    VmText(
        text = if (problem == null) VmDateFormat.fullDateAndTime(chosen) else stringResource(problem),
        style = VmTheme.typography.bodyMd,
        color = if (problem == null) VmTheme.colors.textPrimary else VmTheme.colors.textCritical,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val DAYS_PER_WEEK = 7
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60
private const val MINUTE_STEP = 5
private val CELL_HEIGHT = 44.dp
private val DAY_SIZE = 40.dp
