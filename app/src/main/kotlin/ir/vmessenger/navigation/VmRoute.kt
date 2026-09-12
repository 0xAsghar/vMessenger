package ir.vmessenger.navigation

import kotlinx.serialization.Serializable

/**
 * Every destination of the app as a type-safe Navigation Compose route.
 *
 * Two graphs consume this hierarchy:
 *  - the OUTER [VMessengerNavHost] owns everything except the four tabs, so a
 *    full-screen destination (conversation, image viewer, settings sub-screens)
 *    is never drawn inside the bottom-bar scaffold;
 *  - the INNER tab host inside `HomeRoute` owns [ChatsTab], [ContactsTab],
 *    [MapTab] and [SettingsTab] only.
 *
 * Argument names are part of the contract: a route parameter lands in the
 * destination's `SavedStateHandle` under its own property name, so
 * `Conversation.conversationId` is readable as `savedStateHandle["conversationId"]`
 * or, preferably, via `savedStateHandle.toRoute<VmRoute.Conversation>()`.
 */
@Serializable
sealed interface VmRoute {

    /** Create-or-restore identity; the start destination when no identity exists. */
    @Serializable
    data object Onboarding : VmRoute

    /** The tab shell. Holds the inner NavHost and nothing else. */
    @Serializable
    data object Home : VmRoute

    @Serializable
    data class Conversation(val conversationId: String) : VmRoute

    @Serializable
    data class ContactDetail(val contactId: String) : VmRoute

    @Serializable
    data object NewChat : VmRoute

    @Serializable
    data object NewGroup : VmRoute

    @Serializable
    data class GroupInfo(val groupId: String) : VmRoute

    @Serializable
    data class ImageViewer(val messageId: String) : VmRoute

    @Serializable
    data object PairingMyQr : VmRoute

    @Serializable
    data object PairingScan : VmRoute

    @Serializable
    data object PairingHash : VmRoute

    @Serializable
    data object Identity : VmRoute

    @Serializable
    data object Nodes : VmRoute

    @Serializable
    data object NodesScan : VmRoute

    @Serializable
    data object Debug : VmRoute

    @Serializable
    data object Logs : VmRoute

    @Serializable
    data object About : VmRoute

    @Serializable
    data object Backup : VmRoute

    @Serializable
    data object Update : VmRoute

    @Serializable
    data object BlockedContacts : VmRoute

    /** Bottom-navigation tabs; these live in the inner host only. */
    @Serializable
    data object ChatsTab : VmRoute

    @Serializable
    data object ContactsTab : VmRoute

    @Serializable
    data object MapTab : VmRoute

    @Serializable
    data object SettingsTab : VmRoute
}
