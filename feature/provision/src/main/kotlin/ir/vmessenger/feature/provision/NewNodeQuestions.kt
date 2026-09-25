package ir.vmessenger.feature.provision

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.VmCodeBlock
import ir.vmessenger.core.designsystem.component.VmDialog
import ir.vmessenger.core.designsystem.component.VmSecretField
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.nodesetup.SetupQuestion

/** What the running setup is waiting on: the host key, a sudo password, or a decision. */
@Composable
internal fun Questions(question: SetupQuestion?, viewModel: NewNodeViewModel) {
    when (question) {
        is SetupQuestion.ConfirmHostKey -> Question(
            title = stringResource(R.string.provision_host_key_title),
            no = stringResource(R.string.provision_cancel) to { viewModel.onEvent(NewNodeEvent.HostKeyAnswer(false)) },
            yes = stringResource(R.string.provision_host_key_trust) to {
                viewModel.onEvent(NewNodeEvent.HostKeyAnswer(true))
            },
        ) {
            Body(stringResource(R.string.provision_host_key_body))
            Ltr { VmCodeBlock(text = "${question.hostKey.algorithm}\n${question.hostKey.fingerprint}") }
        }
        is SetupQuestion.SudoPassword -> Question(
            title = stringResource(R.string.provision_sudo_title),
            no = stringResource(R.string.provision_cancel) to {
                viewModel.onEvent(NewNodeEvent.SubmitSudo(cancel = true))
            },
            yes = stringResource(R.string.provision_next) to {
                viewModel.onEvent(NewNodeEvent.SubmitSudo(cancel = false))
            },
        ) {
            val body = if (question.wrong) R.string.provision_sudo_wrong else R.string.provision_sudo_body
            Body(stringResource(body), critical = question.wrong)
            VmSecretField(state = viewModel.sudoPassword, modifier = Modifier.fillMaxWidth())
        }
        is SetupQuestion.Decisions -> Question(
            title = stringResource(R.string.provision_decide_title),
            no = stringResource(R.string.provision_decide_stop) to { viewModel.onEvent(NewNodeEvent.Decide(null)) },
            yes = stringResource(R.string.provision_decide_allow) to {
                viewModel.onEvent(NewNodeEvent.Decide(question.issues.map { it.code }.toSet()))
            },
        ) {
            question.issues.forEach { Body("• ${issueText(it)}") }
        }
        null -> Unit
    }
}

/** A question can't be dismissed by tapping outside it: it is answered one way or the other. */
@Composable
private fun Question(
    title: String,
    no: Pair<String, () -> Unit>,
    yes: Pair<String, () -> Unit>,
    content: @Composable () -> Unit,
) {
    VmDialog(
        onDismissRequest = {},
        title = title,
        buttons = {
            VmTextButton(text = no.first, onClick = no.second)
            VmTextButton(text = yes.first, onClick = yes.second)
        },
    ) { content() }
}
