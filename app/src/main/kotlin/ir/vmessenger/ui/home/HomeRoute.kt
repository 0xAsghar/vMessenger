package ir.vmessenger.ui.home

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.vmessenger.R
import ir.vmessenger.feature.chat.ChatRoute
import ir.vmessenger.feature.contacts.ContactsRoute
import ir.vmessenger.feature.location.LocationRoute
import ir.vmessenger.feature.settings.SettingsRoute
import ir.vmessenger.navigation.Routes

private data class HomeTab(
    val route: String,
    val labelRes: Int,
    val icon: @Composable () -> Unit,
)

@Composable
fun HomeRoute(navigation: HomeNavigation = HomeNavigation()) {
    val tabs = listOf(
        HomeTab(Routes.CHATS, R.string.tab_chats) {
            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
        },
        HomeTab(Routes.CONTACTS, R.string.tab_contacts) {
            Icon(Icons.Outlined.Contacts, contentDescription = null)
        },
        HomeTab(Routes.LOCATION, R.string.tab_location) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null)
        },
        HomeTab(Routes.SETTINGS, R.string.tab_settings) {
            Icon(Icons.Outlined.Settings, contentDescription = null)
        },
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.CHATS

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { HomeBottomBar(tabs, currentRoute, navController) },
    ) { padding ->
        HomeTabNavHost(
            navController = navController,
            navigation = navigation,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun HomeBottomBar(
    tabs: List<HomeTab>,
    currentRoute: String,
    navController: androidx.navigation.NavHostController,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
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

@Composable
private fun HomeTabNavHost(
    navController: androidx.navigation.NavHostController,
    navigation: HomeNavigation,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.CHATS,
        modifier = modifier,
    ) {
        composable(Routes.CHATS) { ChatRoute() }
        composable(Routes.CONTACTS) {
            ContactsRoute(
                onMyQr = navigation.onMyQr,
                onScanQr = navigation.onScanQr,
                onAddByHash = navigation.onAddByHash,
            )
        }
        composable(Routes.LOCATION) { LocationRoute() }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onNavigateToDebug = navigation.onNavigateToDebug,
                onNavigateToAbout = navigation.onNavigateToAbout,
                onNavigateToIdentity = navigation.onNavigateToIdentity,
            )
        }
    }
}
