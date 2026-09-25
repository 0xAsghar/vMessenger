package ir.vmessenger.feature.provision

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.common.network.NodeUrl
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmCodeBlock
import ir.vmessenger.core.designsystem.component.VmLinearProgress
import ir.vmessenger.core.designsystem.component.VmNotice
import ir.vmessenger.core.designsystem.component.VmNoticeKind
import ir.vmessenger.core.designsystem.component.VmStep
import ir.vmessenger.core.designsystem.component.VmStepList
import ir.vmessenger.core.designsystem.component.VmStepState
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.foundation.KeepScreenOn
import ir.vmessenger.core.designsystem.foundation.rememberCopyToClipboard
import ir.vmessenger.core.nodesetup.Issue
import ir.vmessenger.core.nodesetup.IssueCode
import ir.vmessenger.core.nodesetup.NodeSetupState
import ir.vmessenger.core.nodesetup.Reachability
import ir.vmessenger.core.nodesetup.StepId
import ir.vmessenger.core.nodesetup.StepState

/** The server's own steps, issues and log, kept once the run moves past Installing. */
private data class RunView(
    val steps: Map<StepId, StepState> = emptyMap(),
    val issues: List<Issue> = emptyList(),
    val log: List<String> = emptyList(),
)

@Composable
internal fun InstallStep(
    setup: NodeSetupState?,
    viewModel: NewNodeViewModel,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    var run by remember { mutableStateOf(RunView()) }
    LaunchedEffect(setup) {
        if (setup is NodeSetupState.Installing) run = RunView(setup.steps, setup.issues, setup.logTail)
        if (setup is NodeSetupState.Failed && setup.logTail.isNotEmpty()) run = run.copy(log = setup.logTail)
    }
    when (setup) {
        is NodeSetupState.Done -> DoneView(setup, viewModel)
        is NodeSetupState.Failed -> FailedView(
            setup.issue,
            onRetry = { viewModel.onEvent(NewNodeEvent.Retry) },
            onClose
        )
        else -> RunningHeader(setup)
    }
    VmStepList(steps = stepsOf(setup, run.steps), modifier = Modifier.fillMaxWidth())
    run.issues.filter { it.known != IssueCode.APT_NETWORK_RETRY }.forEach { issue ->
        VmNotice(text = issueText(issue), kind = VmNoticeKind.Info)
    }
    if (run.log.isNotEmpty()) LogBlock(run.log)
    if (setup.isRunning()) {
        VmTextButton(
            text = stringResource(R.string.provision_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RunningHeader(setup: NodeSetupState?) {
    KeepScreenOn()
    Body(stringResource(R.string.provision_installing_body))
    if (setup is NodeSetupState.Reconnecting) {
        VmNotice(
            text = stringResource(R.string.provision_reconnecting, setup.attempt),
            kind = VmNoticeKind.Warning,
        )
    }
    if (setup is NodeSetupState.Uploading && setup.totalBytes > 0) {
        VmLinearProgress(
            progress = setup.doneBytes.toFloat() / setup.totalBytes,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LogBlock(log: List<String>) {
    var open by remember { mutableStateOf(false) }
    val copy = rememberCopyToClipboard()
    VmTextButton(
        text = stringResource(if (open) R.string.provision_log_hide else R.string.provision_log_show),
        onClick = { open = !open },
    )
    if (open) {
        val text = log.takeLast(LOG_LINES).joinToString("\n")
        VmCodeBlock(text = text, maxLines = LOG_LINES, onCopy = { copy(text) })
    }
}

private const val LOG_LINES = 60

@Composable
private fun DoneView(done: NodeSetupState.Done, viewModel: NewNodeViewModel) {
    val finishFailed by viewModel.finishFailed.collectAsStateWithLifecycle()
    Heading(stringResource(R.string.provision_done_title))
    Ltr { VmCodeBlock(text = NodeUrl.parse(done.result.relayUrl)?.displayText ?: done.result.relayUrl) }
    if (done.result.mode != "domain-ca") Body(stringResource(R.string.provision_done_pinned))
    if (done.result.mode == "domain-pinned") {
        VmNotice(text = stringResource(R.string.provision_done_le_fallback), kind = VmNoticeKind.Warning)
    }
    val problem = (done.reach as? Reachability.Problem)?.issue
    // A node answering with some other certificate is not the node that was set up: it is not added.
    val blocked = problem?.known == IssueCode.REACH_TLS_MISMATCH
    if (problem != null) {
        VmNotice(text = issueText(problem), kind = if (blocked) VmNoticeKind.Critical else VmNoticeKind.Warning)
    }
    if (finishFailed) {
        VmNotice(text = stringResource(R.string.provision_finish_failed), kind = VmNoticeKind.Critical)
    }
    VmButton(
        text = stringResource(if (problem != null) R.string.provision_add_anyway else R.string.provision_add),
        onClick = { viewModel.onEvent(NewNodeEvent.Finish) },
        enabled = !blocked,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FailedView(issue: Issue, onRetry: () -> Unit, onClose: () -> Unit) {
    VmNotice(
        text = issueText(issue),
        kind = if (issue.known == IssueCode.CANCELLED) VmNoticeKind.Info else VmNoticeKind.Critical,
        title = stringResource(R.string.provision_failed_title),
    )
    VmButton(text = stringResource(R.string.provision_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
    VmTextButton(text = stringResource(R.string.provision_close), onClick = onClose, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun stepsOf(setup: NodeSetupState?, server: Map<StepId, StepState>): List<VmStep> {
    val at = setup.phase()
    fun state(phase: Int): VmStepState = when {
        setup is NodeSetupState.Failed && at == phase -> VmStepState.Failed
        at > phase -> VmStepState.Done
        at == phase -> VmStepState.Running
        else -> VmStepState.Pending
    }
    return buildList {
        add(VmStep(stringResource(R.string.provision_step_connect), state(PHASE_CONNECT)))
        add(VmStep(stringResource(R.string.provision_step_upload), state(PHASE_UPLOAD)))
        server.forEach { (id, s) -> if (id != StepId.UNINSTALL) add(VmStep(stepLabel(id), s.toVm())) }
        add(VmStep(stringResource(R.string.provision_step_verify), state(PHASE_VERIFY)))
    }
}

/** How far the setup got: connecting, uploading, installing, verifying, done. */
private fun NodeSetupState?.phase(): Int = when (this) {
    null, NodeSetupState.Connecting, is NodeSetupState.ConfirmHostKey, NodeSetupState.CheckingServer,
    NodeSetupState.NeedsSudoPassword -> PHASE_CONNECT
    is NodeSetupState.Uploading -> PHASE_UPLOAD
    is NodeSetupState.NeedsDecision, is NodeSetupState.Installing, is NodeSetupState.Reconnecting,
    is NodeSetupState.Failed -> PHASE_INSTALL
    NodeSetupState.Verifying -> PHASE_VERIFY
    is NodeSetupState.Done -> PHASE_DONE
}

private const val PHASE_CONNECT = 0
private const val PHASE_UPLOAD = 1
private const val PHASE_INSTALL = 2
private const val PHASE_VERIFY = 3
private const val PHASE_DONE = 4

private fun StepState.toVm() = when (this) {
    StepState.START, StepState.WAIT -> VmStepState.Running
    StepState.OK -> VmStepState.Done
    StepState.SKIP -> VmStepState.Skipped
    StepState.WARN -> VmStepState.Warning
    StepState.FAIL -> VmStepState.Failed
}
