package ir.vmessenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ir.vmessenger.feature.about.AboutRoute
import ir.vmessenger.feature.chat.ChatRoute
import ir.vmessenger.feature.contacts.ContactsRoute
import ir.vmessenger.feature.debug.DebugRoute
import ir.vmessenger.feature.identity.IdentityRoute
import ir.vmessenger.feature.location.LocationRoute
import ir.vmessenger.feature.pairing.PairingRoute
import ir.vmessenger.feature.settings.SettingsRoute
import ir.vmessenger.ui.home.HomeRoute
import ir.vmessenger.ui.splash.SplashRoute
import kotlinx.coroutines.delay

@Composable
fun VMessengerNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = modifier,
    ) {
        composable(Routes.SPLASH) {
            SplashRoute()
            LaunchedEffect(Unit) {
                delay(1_200)
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            }
        }
        composable(Routes.HOME) {
            HomeRoute()
        }
        composable(Routes.CHATS) { ChatRoute() }
        composable(Routes.CONTACTS) { ContactsRoute() }
        composable(Routes.LOCATION) { LocationRoute() }
        composable(Routes.SETTINGS) { SettingsRoute() }
        composable(Routes.IDENTITY) { IdentityRoute() }
        composable(Routes.PAIRING) { PairingRoute() }
        composable(Routes.DEBUG) { DebugRoute() }
        composable(Routes.ABOUT) { AboutRoute() }
    }
}
