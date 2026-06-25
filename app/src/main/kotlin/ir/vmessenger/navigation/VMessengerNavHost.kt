package ir.vmessenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ir.vmessenger.feature.about.AboutRoute
import ir.vmessenger.feature.debug.DebugRoute
import ir.vmessenger.feature.identity.CreateIdentityRoute
import ir.vmessenger.feature.identity.IdentityRoute
import ir.vmessenger.feature.pairing.AddByHashRoute
import ir.vmessenger.feature.pairing.MyQrRoute
import ir.vmessenger.feature.pairing.QrScannerRoute
import ir.vmessenger.ui.home.HomeNavigation
import ir.vmessenger.ui.home.HomeRoute
import ir.vmessenger.ui.splash.SplashDestination
import ir.vmessenger.ui.splash.SplashRoute
import ir.vmessenger.ui.splash.SplashViewModel
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
            SplashNavigationEffect(navController)
        }
        composable(Routes.CREATE_IDENTITY) {
            CreateIdentityRoute(
                onIdentityCreated = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.CREATE_IDENTITY) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeRoute(
                navigation = HomeNavigation(
                    onMyQr = { navController.navigate(Routes.PAIRING_MY_QR) },
                    onScanQr = { navController.navigate(Routes.PAIRING_SCAN) },
                    onAddByHash = { navController.navigate(Routes.PAIRING_HASH) },
                    onNavigateToDebug = { navController.navigate(Routes.DEBUG) },
                    onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                    onNavigateToIdentity = { navController.navigate(Routes.IDENTITY) },
                ),
            )
        }
        composable(Routes.PAIRING_MY_QR) {
            MyQrRoute(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.PAIRING_SCAN) {
            QrScannerRoute(
                onDone = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PAIRING_HASH) {
            AddByHashRoute(
                onDone = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.IDENTITY) {
            IdentityRoute(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.DEBUG) {
            DebugRoute(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutRoute(onNavigateBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun SplashNavigationEffect(navController: NavHostController) {
    val viewModel: SplashViewModel = hiltViewModel()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    SplashRoute()
    LaunchedEffect(destination) {
        when (destination) {
            SplashDestination.Loading -> Unit
            SplashDestination.Home -> {
                delay(800)
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            }
            SplashDestination.CreateIdentity -> {
                delay(800)
                navController.navigate(Routes.CREATE_IDENTITY) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            }
        }
    }
}
