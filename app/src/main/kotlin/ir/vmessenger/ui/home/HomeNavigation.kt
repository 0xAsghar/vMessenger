package ir.vmessenger.ui.home

/**
 * Every way out of the tab shell.
 *
 * The tabs live in an inner NavHost that owns four destinations and nothing else;
 * anything that needs the whole window is a destination of the OUTER graph and is
 * reached through one of these callbacks. Bundling them in a data class keeps
 * `HomeRoute` to a short parameter list as the surface grows.
 *
 * Screens being rewritten in later phases should code against these signatures:
 * a contact id or a group id is what the tab knows, a conversation id is what the
 * chat list and the "start chat" flow produce.
 */
data class HomeNavigation(
    val onOpenConversation: (conversationId: String) -> Unit = {},
    val onOpenContact: (contactId: String) -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onNewGroup: () -> Unit = {},
    val onOpenGroupInfo: (groupId: String) -> Unit = {},
    val onMyQr: () -> Unit = {},
    val onScanQr: () -> Unit = {},
    val onAddByHash: () -> Unit = {},
    val onNavigateToIdentity: () -> Unit = {},
    val onNavigateToNodes: () -> Unit = {},
    val onNavigateToAbout: () -> Unit = {},
    val onNavigateToBackup: () -> Unit = {},
    val onNavigateToUpdate: () -> Unit = {},
    val onNavigateToBlockedContacts: () -> Unit = {},
    val onNavigateToDebug: () -> Unit = {},
)
