package ir.vmessenger.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
import ir.vmessenger.core.common.text.VmLocale
import ir.vmessenger.core.designsystem.component.VmNavigationBar
import ir.vmessenger.core.designsystem.component.VmNavigationBarItem
import ir.vmessenger.core.designsystem.component.VmScaffold
import ir.vmessenger.feature.chat.ChatRoute
import ir.vmessenger.feature.contacts.ContactsNavigation
import ir.vmessenger.feature.contacts.ContactsRoute
import ir.vmessenger.feature.lock.PinSetupDialog
import ir.vmessenger.feature.map.MapRoute
import ir.vmessenger.feature.settings.SettingsRoute
import ir.vmessenger.feature.settings.update.UpdateBanner
import ir.vmessenger.feature.settings.update.UpdateBannerViewModel
import ir.vmessenger.navigation.VmRoute
import ir.vmessenger.ui.network.AppAlertBanner
import ir.vmessenger.ui.network.LocalAppAlertHost
import ir.vmessenger.ui.network.visibleAppAlert

private const val TAB_FADE_MS = 160

/** What the top of the shell has to clear: the status bar and any cutout reaching below it. */
private val TopInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

private data class HomeTab(
    val route: VmRoute,
    val labelRes: Int,
    val icon: ImageVector,
)

private val HomeTabs = listOf(
    HomeTab(VmRoute.ChatsTab, R.string.tab_chats, Icons.AutoMirrored.Outlined.Chat),
    HomeTab(VmRoute.ContactsTab, R.string.tab_contacts, Icons.Outlined.Contacts),
    HomeTab(VmRoute.MapTab, R.string.tab_map, Icons.Outlined.LocationOn),
    HomeTab(VmRoute.SettingsTab, R.string.tab_settings, Icons.Outlined.Settings),
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
    bannerViewModel: UpdateBannerViewModel = hiltViewModel(),
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

    VmScaffold(
        modifier = modifier,
        // The shell itself pads nothing: the navigation bar consumes the bottom
        // inset and each tab's top app bar consumes the status-bar inset, so the
        // bars stay edge-to-edge and no inset is applied twice.
        contentWindowInsets = WindowInsets(0),
        bottomBar = { HomeBottomBar(navController) },
    ) { padding ->
        Column(
            // consumeWindowInsets: the navigation-bar inset is already spent by the
            // bottom bar, so a tab's own scaffold must not add it a second time.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            // Above the tabs, not inside one: an update and an alert are about the app, not about
            // whichever tab the user happens to be on. The topmost of them takes the top inset —
            // and the tabs below, whose own app bars would otherwise take it again, are told it is
            // spent. The *safe-drawing* top, the one the app bars use: on a phone whose camera
            // cutout runs below its status bar, consuming the status bar alone left a gap.
            val updateVersion by bannerViewModel.availableVersion.collectAsStateWithLifecycle()
            val alertHost = LocalAppAlertHost.current
            val alert = alertHost?.let { visibleAppAlert(it) }
            updateVersion?.let { version ->
                UpdateBanner(
                    version = version,
                    onOpen = navigation.onNavigateToUpdate,
                    onDismiss = bannerViewModel::dismiss,
                    modifier = Modifier.windowInsetsPadding(TopInsets),
                )
            }
            AppAlertBanner(
                alert = alert,
                onDismiss = { alertHost?.dismiss(it) },
                modifier = if (updateVersion == null) Modifier.windowInsetsPadding(TopInsets) else Modifier,
            )
            val bannerOnTop = updateVersion != null || alert != null
            HomeTabNavHost(
                navController = navController,
                navigation = navigation,
                onStartChat = viewModel::startChat,
                onLanguage = viewModel::setLanguage,
                modifier = Modifier
                    .weight(1f)
                    .then(if (bannerOnTop) Modifier.consumeWindowInsets(TopInsets) else Modifier),
            )
        }
    }
}

@Composable
private fun HomeBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    VmNavigationBar {
        HomeTabs.forEach { tab ->
            VmNavigationBarItem(
                // Before the inner graph is set there is no destination yet; the
                // start tab is the honest answer for that one frame.
                selected = destination?.hasRoute(tab.route::class) ?: (tab.route == VmRoute.ChatsTab),
                onClick = { navController.navigateToTab(tab.route) },
                icon = tab.icon,
                label = stringResource(tab.labelRes),
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
    onLanguage: (VmLocale) -> Unit,
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
                onNavigateToActivityLog = navigation.onNavigateToActivityLog,
                onLanguage = onLanguage,
                onNavigateToUpdate = navigation.onNavigateToUpdate,
                pinDialog = { onDone -> PinSetupDialog(onDone = onDone) },
            )
        }
    }
}
