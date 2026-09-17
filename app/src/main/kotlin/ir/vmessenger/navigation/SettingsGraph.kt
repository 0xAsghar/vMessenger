package ir.vmessenger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ir.vmessenger.feature.about.AboutRoute
import ir.vmessenger.feature.debug.DebugRoute
import ir.vmessenger.feature.debug.LogsRoute
import ir.vmessenger.feature.identity.IdentityRoute
import ir.vmessenger.feature.settings.NodeQrScannerRoute
import ir.vmessenger.feature.settings.NodesRoute
import ir.vmessenger.feature.settings.update.UpdateRoute
/** Everything reachable from the settings tab, as full screens outside the tab shell. */
internal fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable<VmRoute.Identity> { entry ->
        IdentityRoute(onNavigateBack = { navController.popIfCurrent(entry) })
    }
    composable<VmRoute.About> { entry ->
        AboutRoute(onNavigateBack = { navController.popIfCurrent(entry) })
    }
    composable<VmRoute.Nodes> { entry ->
        NodesRoute(
            onNavigateBack = { navController.popIfCurrent(entry) },
            onNavigateToScan = { navController.navigate(VmRoute.NodesScan) },
        )
    }
    composable<VmRoute.NodesScan> { entry ->
        NodeQrScannerRoute(
            onDone = { navController.popIfCurrent(entry) },
            onNavigateBack = { navController.popIfCurrent(entry) },
        )
    }
    composable<VmRoute.Update> { entry ->
        UpdateRoute(onBack = { navController.popIfCurrent(entry) })
    }
}

/** Debug and Logs, gated so they cannot be reached unless developer mode is on. */
internal fun NavGraphBuilder.developerToolsGraph(navController: NavHostController) {
    composable<VmRoute.Debug> { entry ->
        DeveloperToolsGate(onDenied = { navController.popIfCurrent(entry) }) {
            DebugRoute(
                onNavigateBack = { navController.popIfCurrent(entry) },
                onNavigateToLogs = { navController.navigate(VmRoute.Logs) },
            )
        }
    }
    composable<VmRoute.Logs> { entry ->
        DeveloperToolsGate(onDenied = { navController.popIfCurrent(entry) }) {
            LogsRoute(onNavigateBack = { navController.popIfCurrent(entry) })
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
