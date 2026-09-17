package ir.vmessenger.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.feature.pairing.AddByHashRoute
import ir.vmessenger.feature.pairing.MyQrRoute
import ir.vmessenger.feature.pairing.QrScannerRoute

/** The three ways to add a contact: show my QR, scan one, type a hash. */
internal fun NavGraphBuilder.pairingGraph(navController: NavHostController) {
    composable<VmRoute.PairingMyQr> { entry ->
        MyQrRoute(onNavigateBack = { navController.popIfCurrent(entry) })
    }
    composable<VmRoute.PairingScan> { entry ->
        QrScannerRoute(
            onDone = { navController.popIfCurrent(entry) },
            onNavigateBack = { navController.popIfCurrent(entry) },
        )
    }
    composable<VmRoute.PairingHash> { entry ->
        AddByHashRoute(
            onDone = { navController.popIfCurrent(entry) },
            onNavigateBack = { navController.popIfCurrent(entry) },
        )
    }
}
