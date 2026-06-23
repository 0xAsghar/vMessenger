package ir.vmessenger.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
fun HomeRoute() {
    val tabs = listOf(
        HomeTab(Routes.CHATS, R.string.tab_chats) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
        },
        HomeTab(Routes.CONTACTS, R.string.tab_contacts) {
            Icon(Icons.Default.Contacts, contentDescription = null)
        },
        HomeTab(Routes.LOCATION, R.string.tab_location) {
            Icon(Icons.Default.LocationOn, contentDescription = null)
        },
        HomeTab(Routes.SETTINGS, R.string.tab_settings) {
            Icon(Icons.Default.Settings, contentDescription = null)
        },
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.CHATS

    Scaffold(
        bottomBar = {
            NavigationBar {
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
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHATS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.CHATS) { ChatRoute() }
            composable(Routes.CONTACTS) { ContactsRoute() }
            composable(Routes.LOCATION) { LocationRoute() }
            composable(Routes.SETTINGS) { SettingsRoute() }
        }
    }
}
