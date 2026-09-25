package ir.vmessenger.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.error.toUiText
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The node question, asked once before an identity is created.
 *
 * A node is what makes reaching anyone possible, so this is a real choice rather than a screen to
 * tap through: the test nodes are offered with their expiry stated, adding or running your own is
 * offered inline, and skipping is allowed but only behind the warning that says what it costs.
 */
@Composable
fun NodeSetupRoute(
    onDone: () -> Unit,
    onCreateNode: () -> Unit,
    provisioned: Boolean,
    modifier: Modifier = Modifier,
    viewModel: NodeSetupViewModel = hiltViewModel(),
) {
    // The New node wizard set a node up and added it: that is the person's own node.
    LaunchedEffect(provisioned) { if (provisioned) viewModel.onProvisioned(onDone) }
    val step by viewModel.step.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var confirmSkip by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    VMessengerScaffold(
        title = stringResource(R.string.node_setup_title),
        onNavigateBack = { viewModel.onStep(NodeSetupStep.Choose) }
            .takeIf { step != NodeSetupStep.Choose },
        scrolled = scroll.canScrollBackward,
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(VmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            when (step) {
                NodeSetupStep.Choose -> ChooseStep(
                    busy = busy,
                    onStep = viewModel::onStep,
                    onCreateNode = onCreateNode,
                    onUseTestNodes = { viewModel.onUseTestNodes(onDone) },
                    onSkip = { confirmSkip = true },
                )
                NodeSetupStep.AddNode -> AddressStep(
                    busy = busy,
                    error = error?.toUiText(),
                    onSubmit = { input -> viewModel.onSubmitAddress(input, onDone) },
                )
            }
        }
    }
    if (confirmSkip) {
        ConfirmDialog(
            title = stringResource(R.string.node_setup_skip),
            body = stringResource(R.string.node_setup_skip_warning),
            confirmLabel = stringResource(R.string.node_setup_skip_confirm),
            onConfirm = {
                confirmSkip = false
                viewModel.onSkip(onDone)
            },
            onDismiss = { confirmSkip = false },
        )
    }
}

@Composable
private fun Explainer(text: String, emphasis: Boolean = false) {
    VmText(
        text = text,
        style = VmTheme.typography.bodyMd,
        color = if (emphasis) {
            VmTheme.colors.textCritical
        } else {
            VmTheme.colors.textSecondary
        },
    )
}

@Composable
private fun ChooseStep(
    busy: Boolean,
    onStep: (NodeSetupStep) -> Unit,
    onCreateNode: () -> Unit,
    onUseTestNodes: () -> Unit,
    onSkip: () -> Unit,
) {
    Explainer(stringResource(R.string.node_setup_body))
    VmButton(
        text = stringResource(R.string.node_setup_use_test),
        onClick = onUseTestNodes,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    )
    // Stated up front rather than in a footnote: picking these is picking an expiry date.
    Explainer(stringResource(R.string.node_setup_test_warning), emphasis = true)
    VmOutlinedButton(
        text = stringResource(R.string.node_setup_add),
        onClick = { onStep(NodeSetupStep.AddNode) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    )
    VmOutlinedButton(
        text = stringResource(R.string.node_setup_create),
        onClick = onCreateNode,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    )
    VmTextButton(
        text = stringResource(R.string.node_setup_skip),
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
    )
}

@Composable
private fun AddressStep(
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Explainer(stringResource(R.string.node_setup_add_body))
    VmTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        config = VmTextFieldConfig(
            label = stringResource(R.string.node_setup_address_label),
            isError = error != null,
            supportingText = error,
        ),
    )
    VmButton(
        text = stringResource(R.string.node_setup_address_save),
        onClick = { onSubmit(input) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy && input.isNotBlank(),
    )
}
