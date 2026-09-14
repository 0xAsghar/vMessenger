package ir.vmessenger.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

private val DotSize = 10.dp

/**
 * Past this the row would be wider than a phone. The count in the spoken description stays
 * truthful either way, and a PIN this long is being typed by someone watching the dots stop.
 */
private const val MAX_VISIBLE_DOTS = 16

private val KEYPAD_ROWS = listOf(1..3, 4..6, 7..9)

/** The digits already typed, as dots — the count is the only thing that may ever be shown. */
@Composable
internal fun PinDots(length: Int, modifier: Modifier = Modifier) {
    val spoken = stringResource(
        R.string.app_lock_entered_digits,
        VmTextFormat.persianDigits(length.toString()),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = VmSpacing.xl)
            .semantics { contentDescription = spoken },
    ) {
        repeat(length.coerceAtMost(MAX_VISIBLE_DOTS)) {
            Box(
                modifier = Modifier
                    .size(DotSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * The numeric pad. [onSubmit] is null where the surrounding surface already has a confirm button,
 * which is what the setup dialog does; the lock screen has nowhere else to put one.
 */
@Composable
internal fun PinKeypad(
    entry: PinEntry,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VmSpacing.sm)) {
        KEYPAD_ROWS.forEach { digits ->
            KeypadRow {
                digits.forEach { digit ->
                    DigitKey(digit = digit, enabled = enabled, onAppend = entry::append)
                }
            }
        }
        KeypadRow {
            ActionKey(
                icon = Icons.AutoMirrored.Filled.Backspace,
                description = stringResource(R.string.app_lock_key_backspace),
                enabled = enabled && entry.length > 0,
                onClick = entry::backspace,
            )
            DigitKey(digit = 0, enabled = enabled, onAppend = entry::append)
            if (onSubmit == null) {
                Spacer(modifier = Modifier.weight(1f))
            } else {
                ActionKey(
                    icon = Icons.Filled.Check,
                    description = stringResource(R.string.app_lock_key_submit),
                    enabled = enabled && entry.isSubmittable,
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun KeypadRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm), content = content)
}

@Composable
private fun RowScope.DigitKey(digit: Int, enabled: Boolean, onAppend: (Char) -> Unit) {
    // The label is Persian and the character appended is ASCII. The verifier hashes exactly the
    // characters it is handed, so every way into this app has to agree on which zero it means.
    val label = remember(digit) { VmTextFormat.persianDigits(digit.toString()) }
    KeyButton(enabled = enabled, onClick = { onAppend('0' + digit) }) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun RowScope.ActionKey(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    KeyButton(enabled = enabled, onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/** One key: a third of the row wide, and never shorter than a finger. */
@Composable
private fun RowScope.KeyButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(VmSpacing.xs),
        modifier = Modifier
            .weight(1f)
            .height(VmSizes.touchTarget),
    ) {
        content()
    }
}
