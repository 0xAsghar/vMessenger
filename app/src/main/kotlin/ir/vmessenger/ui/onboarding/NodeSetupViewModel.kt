package ir.vmessenger.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.datastore.NodeSetupChoice
import ir.vmessenger.core.datastore.NodeSetupPreferences
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.usecase.nodes.AddNetworkNodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which part of the node question is on screen. */
enum class NodeSetupStep { Choose, AddNode }

/**
 * The node question, asked once before an identity exists.
 *
 * Choosing the test nodes records nothing but the choice: the built-in nodes are seeded by the
 * network join, as they always were, and only [NodeSetupChoice.Skipped] suppresses that. So "use
 * test nodes" and "skip" differ in exactly one stored value rather than in two seeding paths.
 */
@HiltViewModel
class NodeSetupViewModel @Inject constructor(
    private val nodeSetupPreferences: NodeSetupPreferences,
    private val addNetworkNode: AddNetworkNodeUseCase,
) : ViewModel() {
    private val _step = MutableStateFlow(NodeSetupStep.Choose)
    val step: StateFlow<NodeSetupStep> = _step.asStateFlow()

    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun onStep(step: NodeSetupStep) {
        _error.value = null
        _step.value = step
    }

    fun onUseTestNodes(onDone: () -> Unit) = record(NodeSetupChoice.TestNodes, onDone)

    /** The New node wizard set a node up and added it. */
    fun onProvisioned(onDone: () -> Unit) = record(NodeSetupChoice.Custom, onDone)

    /** Continuing with none. The warning is the screen's job; this only records the answer. */
    fun onSkip(onDone: () -> Unit) = record(NodeSetupChoice.Skipped, onDone)

    /**
     * Registers the address the user typed. A relay is assumed when the input carries no role of
     * its own: it is the node kind an install cannot do without, and a `vmnode:` link overrides it.
     */
    fun onSubmitAddress(input: String, onDone: () -> Unit) {
        if (_busy.value || input.isBlank()) return
        _busy.value = true
        viewModelScope.launch {
            when (val result = addNetworkNode(input.trim(), NetworkNodeRole.RELAY)) {
                is AppResult.Success -> record(NodeSetupChoice.Custom, onDone)
                is AppResult.Error -> {
                    _error.value = result.error
                    _busy.value = false
                }
            }
        }
    }

    private fun record(choice: NodeSetupChoice, onDone: () -> Unit) {
        _busy.value = true
        viewModelScope.launch {
            nodeSetupPreferences.setChoice(choice)
            _busy.value = false
            onDone()
        }
    }
}
