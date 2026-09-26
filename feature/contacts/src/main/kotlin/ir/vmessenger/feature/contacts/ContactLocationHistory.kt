package ir.vmessenger.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlinx.collections.immutable.ImmutableList
import java.util.Locale
import kotlin.math.roundToInt

private const val COLLAPSED_ROWS = 10

/**
 * Where a contact has been, as they shared it with us: every position inside the retention window,
 * newest first. The route itself is drawn on the location card above; this is the list of changes.
 */
@Composable
internal fun ContactLocationHistory(history: ImmutableList<LocationHistoryEntry>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.contact_detail_history_section))
        if (history.isEmpty()) {
            Note(stringResource(R.string.contact_detail_history_empty))
        } else {
            val shown = if (expanded) history else history.take(COLLAPSED_ROWS)
            shown.forEach { HistoryRow(it) }
            if (!expanded && history.size > COLLAPSED_ROWS) {
                val count = VmTextFormat.digits(history.size.toString())
                VmTextButton(
                    text = stringResource(R.string.contact_detail_history_more, count),
                    onClick = { expanded = true },
                    modifier = Modifier.padding(horizontal = VmSpacing.sm),
                )
            }
            Note(stringResource(R.string.contact_detail_history_note))
        }
    }
}

@Composable
private fun HistoryRow(entry: LocationHistoryEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        VmIcon(
            imageVector = Icons.Outlined.Place,
            contentDescription = null,
            tint = VmTheme.colors.iconSecondary,
            size = VmSizes.iconSm,
        )
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = VmDateFormat.dayAndTime(entry.sampledAtUnixMs),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textPrimary,
            )
            // Coordinates stay left to right with ASCII digits in both languages.
            VmText(
                text = VmTextFormat.isolate(coordinates(entry)),
                style = VmTheme.typography.bodySm,
                color = VmTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    VmText(
        text = text,
        style = VmTheme.typography.bodySm,
        color = VmTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xs),
    )
}

private fun coordinates(entry: LocationHistoryEntry): String =
    String.format(Locale.US, "%.5f, %.5f  ±%d m", entry.latitude, entry.longitude, entry.accuracyM.roundToInt())
