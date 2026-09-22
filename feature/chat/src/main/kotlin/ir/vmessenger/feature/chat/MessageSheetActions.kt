package ir.vmessenger.feature.chat

import androidx.compose.runtime.Stable

/**
 * What the long-press sheet can do to one message.
 *
 * Bundled because the sheet reached ten parameters once editing and delete-for-everyone joined it,
 * and a holder keeps the composable readable as well as inside the parameter budget.
 */
@Stable
@Suppress("LongParameterList") // one callback per row the sheet can show; a second holder would only nest
internal class MessageSheetActions(
    val onReply: () -> Unit,
    val onEdit: () -> Unit,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
    val onInfo: () -> Unit,
    /** True asks every recipient to drop it too; false erases only the local copy. */
    val onDelete: (forEveryone: Boolean) -> Unit,
    val onDismiss: () -> Unit,
)
