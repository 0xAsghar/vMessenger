package ir.vmessenger.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.feature.chat.ConversationRoute
import ir.vmessenger.feature.chat.ImageViewerRoute
import ir.vmessenger.feature.chat.NewChatRoute
import ir.vmessenger.feature.chat.group.GroupInfoRoute
import ir.vmessenger.feature.chat.group.NewGroupRoute

/**
 * Conversation and the chat-adjacent destinations.
 *
 * These live in the OUTER graph on purpose: drawn here the conversation gets the
 * whole window, so the bottom navigation bar can no longer sit on top of the
 * composer, and the composer's own `navigationBarsPadding().imePadding()` is the
 * single source of bottom inset for the screen.
 */
internal fun NavGraphBuilder.chatGraph(navController: NavHostController) {
    composable<VmRoute.Conversation> { entry ->
        ConversationRoute(
            onBack = { navController.popIfCurrent(entry) },
            onOpenContact = { contactId ->
                navController.navigate(VmRoute.ContactDetail(contactId)) { launchSingleTop = true }
            },
            onOpenGroup = { groupId ->
                navController.navigate(VmRoute.GroupInfo(groupId)) { launchSingleTop = true }
            },
            onOpenImage = { messageId ->
                navController.navigate(VmRoute.ImageViewer(messageId)) { launchSingleTop = true }
            },
        )
    }
    composable<VmRoute.NewChat> { entry ->
        NewChatRoute(
            onBack = { navController.popIfCurrent(entry) },
            onNewGroup = { navController.navigate(VmRoute.NewGroup) },
            // The picker is a step on the way to the chat, not a place to come back to.
            onOpenConversation = { conversationId ->
                navController.navigate(VmRoute.Conversation(conversationId)) {
                    popUpTo<VmRoute.NewChat> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable<VmRoute.NewGroup> { entry ->
        NewGroupRoute(
            onBack = { navController.popIfCurrent(entry) },
            // Like the contact picker: a step on the way to the chat, not a place to come back to.
            onGroupCreated = { conversationId ->
                navController.navigate(VmRoute.Conversation(conversationId)) {
                    popUpTo<VmRoute.NewChat> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable<VmRoute.GroupInfo> { entry ->
        // The screen pops itself once the group is gone (left or closed), so there
        // is no leave/close result to handle here.
        GroupInfoRoute(
            onBack = { navController.popIfCurrent(entry) },
            onOpenContact = { contactId ->
                navController.navigate(VmRoute.ContactDetail(contactId)) { launchSingleTop = true }
            },
        )
    }
    composable<VmRoute.ImageViewer> { entry ->
        // Full-bleed by contract: the viewer draws behind the system bars and pads its
        // own close button with safeDrawingPadding().
        ImageViewerRoute(onBack = { navController.popIfCurrent(entry) })
    }
}
