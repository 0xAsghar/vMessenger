package ir.vmessenger.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.feature.identity.CreateIdentityRoute
import ir.vmessenger.ui.onboarding.NodeSetupRoute
import ir.vmessenger.ui.onboarding.rememberBackgroundActivityThen
import ir.vmessenger.ui.onboarding.rememberLocationPermissionThen

/**
 * First run: the node question, then the ID. Background activity is asked for as the node step ends,
 * location as the ID step ends; either way the person moves on.
 */
internal fun NavGraphBuilder.onboardingGraph(navController: NavHostController) {
    composable<VmRoute.NodeSetup> { entry ->
        val provisioned by entry.savedStateHandle.getStateFlow(NODE_PROVISIONED, false)
            .collectAsStateWithLifecycle()
        val backgroundThen = rememberBackgroundActivityThen()
        NodeSetupRoute(
            onDone = {
                backgroundThen {
                    navController.navigate(VmRoute.Onboarding) {
                        popUpTo<VmRoute.NodeSetup> { inclusive = true }
                    }
                }
            },
            onCreateNode = { navController.navigate(VmRoute.NewNode()) },
            provisioned = provisioned,
        )
    }
    composable<VmRoute.Onboarding> {
        val locationThen = rememberLocationPermissionThen()
        CreateIdentityRoute(
            onIdentityCreated = {
                locationThen {
                    navController.navigate(VmRoute.Home) {
                        popUpTo<VmRoute.Onboarding> { inclusive = true }
                    }
                }
            },
        )
    }
}
