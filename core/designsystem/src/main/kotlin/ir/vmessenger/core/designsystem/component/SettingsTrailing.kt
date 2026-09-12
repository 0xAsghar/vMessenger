package ir.vmessenger.core.designsystem.component

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/** What sits at the end of a [SettingsRow]. */
@Stable
sealed interface SettingsTrailing {

    /** Navigates somewhere; draws a direction-aware chevron. */
    data object Chevron : SettingsTrailing

    /** Nothing at all; the row is informational or handled by its own click. */
    data object None : SettingsTrailing

    /** A toggle. The row click and the switch call the same [onCheckedChange]. */
    @Stable
    data class Switch(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : SettingsTrailing

    /** A small accent pill, e.g. "نسخهٔ جدید". */
    @Immutable
    data class Badge(val text: String) : SettingsTrailing

    /** A muted value, e.g. the last update check. */
    @Immutable
    data class Text(val value: String) : SettingsTrailing
}
