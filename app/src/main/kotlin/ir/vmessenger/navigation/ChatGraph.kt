package ir.vmessenger.navigation

import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.R
import ir.vmessenger.feature.chat.ConversationRoute
import ir.vmessenger.ui.placeholder.ComingSoonRoute

/**
 * Conversation and the chat-adjacent destinations.
 *
 * These live in the OUTER graph on purpose: drawn here the conversation gets the
 * whole window, so the bottom navigation bar can no longer sit on top of the
 * composer, and the composer's own `navigationBarsPadding().imePadding()` is the
 * single source of bottom inset for the screen.
 */
internal fun NavGraphBuilder.chatGraph(navController: NavHostController) {
    composable<VmRoute.Conversation> {
        ConversationRoute(onBack = { navController.popBackStack() })
    }
    composable<VmRoute.NewChat> {
        ComingSoonRoute(
            title = stringResource(R.string.route_new_chat),
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable<VmRoute.NewGroup> {
        ComingSoonRoute(
            title = stringResource(R.string.route_new_group),
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable<VmRoute.GroupInfo> {
        ComingSoonRoute(
            title = stringResource(R.string.route_group_info),
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable<VmRoute.AddGroupMembers> {
        ComingSoonRoute(
            title = stringResource(R.string.route_add_group_members),
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable<VmRoute.ImageViewer> {
        // Full-bleed by contract: no scaffold insets, controls pad themselves.
        ComingSoonRoute(
            title = stringResource(R.string.route_image_viewer),
            onNavigateBack = { navController.popBackStack() },
            fullBleed = true,
        )
    }
}
