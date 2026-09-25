package ir.vmessenger.feature.provision

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.foundation.ExcludeFromAutofill
import ir.vmessenger.core.designsystem.foundation.RequireSecureWindow
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.nodesetup.NodeSetupState

/**
 * New node: SSH credentials in, a working node out (docs/Deployment.md §0). The window is secure and
 * kept out of autofill for the whole wizard, since it holds a password or a key.
 */
@Composable
fun NewNodeRoute(
    onNavigateBack: () -> Unit,
    onFinished: () -> Unit,
    viewModel: NewNodeViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val question by viewModel.question.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    var confirmCancel by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    RequireSecureWindow()
    ExcludeFromAutofill()
    LaunchedEffect(finished) { if (finished) onFinished() }

    val leave = {
        when (leaveAction(form, setup)) {
            Leave.AskToCancel -> confirmCancel = true
            Leave.StepBack -> viewModel.onEvent(NewNodeEvent.Back)
            Leave.Close -> {
                viewModel.onEvent(NewNodeEvent.Leave)
                onNavigateBack()
            }
        }
    }
    BackHandler(onBack = leave)
    VMessengerScaffold(
        title = stringResource(
            if (form.updating != null) R.string.provision_update_title else R.string.provision_title
        ),
        onNavigateBack = leave,
        scrolled = scroll.canScrollBackward,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding).verticalScroll(scroll).padding(VmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            when (form.step) {
                NewNodeStep.Intro -> IntroStep(viewModel::onEvent)
                NewNodeStep.Server -> ServerStep(form, viewModel)
                NewNodeStep.Address -> AddressStep(form, viewModel::onEvent)
                NewNodeStep.Security -> SecurityStep(form, viewModel::onEvent)
                NewNodeStep.Review -> ReviewStep(form, viewModel::onEvent)
                NewNodeStep.Install -> InstallStep(
                    setup,
                    viewModel,
                    onCancel = { confirmCancel = true },
                    onClose = leave
                )
            }
        }
    }
    Questions(question, viewModel)
    if (confirmCancel) {
        CancelDialog(
            onConfirm = {
                confirmCancel = false
                viewModel.onEvent(NewNodeEvent.Cancel)
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun CancelDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.provision_cancel_title),
        body = stringResource(R.string.provision_cancel_body),
        confirmLabel = stringResource(R.string.provision_cancel_confirm),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private enum class Leave { AskToCancel, StepBack, Close }

/** Back during a running setup asks first; in the form it goes a step back; otherwise it closes. */
private fun leaveAction(form: NewNodeForm, setup: NodeSetupState?): Leave {
    val firstStep = if (form.updating != null) NewNodeStep.Server else NewNodeStep.Intro
    return when {
        form.step == NewNodeStep.Install && setup.isRunning() -> Leave.AskToCancel
        form.step == NewNodeStep.Install || form.step == firstStep -> Leave.Close
        else -> Leave.StepBack
    }
}

internal fun NodeSetupState?.isRunning() =
    this != null && this !is NodeSetupState.Done && this !is NodeSetupState.Failed
