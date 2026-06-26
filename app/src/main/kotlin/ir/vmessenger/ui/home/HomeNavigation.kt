package ir.vmessenger.ui.home

data class HomeNavigation(
    val onMyQr: () -> Unit = {},
    val onScanQr: () -> Unit = {},
    val onAddByHash: () -> Unit = {},
    val onNavigateToDebug: () -> Unit = {},
    val onNavigateToAbout: () -> Unit = {},
    val onNavigateToIdentity: () -> Unit = {},
)

data class HomeChatNavigation(
    val openConversationId: String?,
    val onOpenConversationConsumed: () -> Unit,
    val onStartChat: (String) -> Unit,
)
