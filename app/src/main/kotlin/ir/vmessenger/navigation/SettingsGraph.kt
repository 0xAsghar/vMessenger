package ir.vmessenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.R
import ir.vmessenger.feature.about.AboutRoute
import ir.vmessenger.feature.debug.DebugRoute
import ir.vmessenger.feature.debug.LogsRoute
import ir.vmessenger.feature.identity.IdentityRoute
import ir.vmessenger.feature.settings.NodeQrScannerRoute
import ir.vmessenger.feature.settings.NodesRoute
import ir.vmessenger.ui.placeholder.ComingSoonRoute

/** Everything reachable from the settings tab, as full screens outside the tab shell. */
internal fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable<VmRoute.Identity> {
        IdentityRoute(onNavigateBack = { navController.popBackStack() })
    }
    composable<VmRoute.About> {
        AboutRoute(onNavigateBack = { navController.popBackStack() })
    }
    composable<VmRoute.Nodes> {
        NodesRoute(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToScan = { navController.navigate(VmRoute.NodesScan) },
        )
    }
    composable<VmRoute.NodesScan> {
        NodeQrScannerRoute(
            onDone = { navController.popBackStack() },
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable<VmRoute.Backup> {
        ComingSoonRoute(
            title = stringResource(R.string.route_backup),
            onNavigateBack = { navController.popBackStack() },
        )
    }
    composable<VmRoute.Update> {
        ComingSoonRoute(
            title = stringResource(R.string.route_update),
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

/** Debug and Logs, gated so they cannot be reached unless developer mode is on. */
internal fun NavGraphBuilder.developerToolsGraph(navController: NavHostController) {
    composable<VmRoute.Debug> {
        DeveloperToolsGate(onDenied = { navController.popBackStack() }) {
            DebugRoute(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToLogs = { navController.navigate(VmRoute.Logs) },
            )
        }
    }
    composable<VmRoute.Logs> {
        DeveloperToolsGate(onDenied = { navController.popBackStack() }) {
            LogsRoute(onNavigateBack = { navController.popBackStack() })
        }
    }
}

/**
 * Renders [content] only while the developer tools are unlocked; otherwise pops
 * the destination, so a release build cannot reach Debug or Logs even through a
 * stale back stack entry after developer mode is switched off again.
 */
@Composable
private fun DeveloperToolsGate(
    onDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    val viewModel: DeveloperToolsViewModel = hiltViewModel()
    val enabled by viewModel.developerToolsEnabled.collectAsStateWithLifecycle()
    when (enabled) {
        null -> Unit
        true -> content()
        false -> LaunchedEffect(Unit) { onDenied() }
    }
}
