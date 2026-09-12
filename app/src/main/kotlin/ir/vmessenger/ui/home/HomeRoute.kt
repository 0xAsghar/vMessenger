package ir.vmessenger.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.vmessenger.R
import ir.vmessenger.feature.chat.ChatRoute
import ir.vmessenger.feature.contacts.ContactsNavigation
import ir.vmessenger.feature.contacts.ContactsRoute
import ir.vmessenger.feature.map.MapRoute
import ir.vmessenger.feature.settings.SettingsRoute
import ir.vmessenger.navigation.VmRoute

private const val TAB_FADE_MS = 160

private data class HomeTab(
    val route: VmRoute,
    val labelRes: Int,
    val icon: @Composable () -> Unit,
)

private val HomeTabs = listOf(
    HomeTab(VmRoute.ChatsTab, R.string.tab_chats) {
        Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
    },
    HomeTab(VmRoute.ContactsTab, R.string.tab_contacts) {
        Icon(Icons.Outlined.Contacts, contentDescription = null)
    },
    HomeTab(VmRoute.MapTab, R.string.tab_map) {
        Icon(Icons.Outlined.LocationOn, contentDescription = null)
    },
    HomeTab(VmRoute.SettingsTab, R.string.tab_settings) {
        Icon(Icons.Outlined.Settings, contentDescription = null)
    },
)

/**
 * The tab shell: a bottom bar and an inner NavHost holding the four tabs, nothing
 * else. Every full-screen destination is reached through [navigation], so the
 * navigation bar can never be drawn over a conversation composer again.
 */
@Composable
fun HomeRoute(
    navigation: HomeNavigation = HomeNavigation(),
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val startedConversationId by viewModel.openConversationId.collectAsStateWithLifecycle()

    // "Chat with this contact" resolves to a conversation id first; the outer
    // graph then owns the destination.
    LaunchedEffect(startedConversationId) {
        startedConversationId?.let { conversationId ->
            navigation.onOpenConversation(conversationId)
            viewModel.consumeOpenConversation()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The shell itself pads nothing: the navigation bar consumes the bottom
        // inset and each tab's top app bar consumes the status-bar inset, so the
        // bars stay edge-to-edge and no inset is applied twice.
        contentWindowInsets = WindowInsets(0),
        bottomBar = { HomeBottomBar(navController) },
    ) { padding ->
        HomeTabNavHost(
            navController = navController,
            navigation = navigation,
            onStartChat = viewModel::startChat,
            // consumeWindowInsets: the navigation-bar inset is already spent by the
            // bottom bar, so a tab's own scaffold must not add it a second time.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        )
    }
}

@Composable
private fun HomeBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        HomeTabs.forEach { tab ->
            NavigationBarItem(
                // Before the inner graph is set there is no destination yet; the
                // start tab is the honest answer for that one frame.
                selected = destination?.hasRoute(tab.route::class) ?: (tab.route == VmRoute.ChatsTab),
                onClick = { navController.navigateToTab(tab.route) },
                icon = tab.icon,
                label = { Text(text = stringResource(tab.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onBackground,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** Tab switching keeps one entry per tab and restores the tab's own state. */
private fun NavHostController.navigateToTab(route: VmRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun HomeTabNavHost(
    navController: NavHostController,
    navigation: HomeNavigation,
    onStartChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = VmRoute.ChatsTab,
        modifier = modifier,
        // Tabs are siblings, not a hierarchy: they cross-fade instead of sliding.
        enterTransition = { fadeIn(tween(TAB_FADE_MS)) },
        exitTransition = { fadeOut(tween(TAB_FADE_MS)) },
        popEnterTransition = { fadeIn(tween(TAB_FADE_MS)) },
        popExitTransition = { fadeOut(tween(TAB_FADE_MS)) },
    ) {
        composable<VmRoute.ChatsTab> {
            ChatRoute(
                onOpenConversation = navigation.onOpenConversation,
                onNewChat = navigation.onNewChat,
            )
        }
        composable<VmRoute.ContactsTab> {
            ContactsRoute(
                navigation = ContactsNavigation(
                    onMyQr = navigation.onMyQr,
                    onScanQr = navigation.onScanQr,
                    onAddByHash = navigation.onAddByHash,
                    onOpenContact = navigation.onOpenContact,
                    onStartChat = onStartChat,
                ),
            )
        }
        composable<VmRoute.MapTab> { MapRoute() }
        composable<VmRoute.SettingsTab> {
            SettingsRoute(
                onNavigateToDebug = navigation.onNavigateToDebug,
                onNavigateToNodes = navigation.onNavigateToNodes,
                onNavigateToAbout = navigation.onNavigateToAbout,
                onNavigateToIdentity = navigation.onNavigateToIdentity,
                onNavigateToBlockedContacts = navigation.onNavigateToBlockedContacts,
            )
        }
    }
}
