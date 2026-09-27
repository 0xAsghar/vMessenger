package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BootstrapManager @Inject constructor(
    providers: Set<@JvmSuppressWildcards BootstrapProvider>,
) {
    private val providers = providers.sortedByDescending { it.priority }

    suspend fun collectNodes(): AppResult<List<BootstrapNode>> {
        val merged = mutableListOf<BootstrapNode>()
        val seen = mutableSetOf<String>()
        for (provider in providers) {
            mergeProviderNodes(provider.nodes(), merged, seen)
        }
        return if (merged.isEmpty()) {
            AppResult.Error(AppError.NoBootstrapNode)
        } else {
            AppResult.Success(merged)
        }
    }

    private fun mergeProviderNodes(
        result: AppResult<List<BootstrapNode>>,
        merged: MutableList<BootstrapNode>,
        seen: MutableSet<String>,
    ) {
        if (result !is AppResult.Success) return
        result.data.forEach { node ->
            if (seen.add(node.address)) {
                merged.add(node)
            }
        }
    }
}
