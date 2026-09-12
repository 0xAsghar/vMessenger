package ir.vmessenger.feature.chat

import androidx.compose.runtime.Stable

/**
 * Everything a bubble can do. Bundled into one object so every composable that carries it
 * stays inside the parameter budget, and so the callbacks keep a stable identity across
 * recompositions of the list.
 */
@Stable
internal class MessageActions(
    val onLongPress: (String) -> Unit,
    val onReply: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onOpenImage: (String) -> Unit,
    val onOpenFile: (ChatItem.Message) -> Unit,
    val onJumpToQuoted: (String) -> Unit,
)
