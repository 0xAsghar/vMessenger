package ir.vmessenger.feature.provision

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.nodesetup.InstallOptions
import ir.vmessenger.core.nodesetup.NodeSetupRequest
import ir.vmessenger.core.nodesetup.NodeSetupSession
import ir.vmessenger.core.nodesetup.NodeSetupState
import ir.vmessenger.core.nodesetup.SetupQuestion
import ir.vmessenger.core.ssh.HostKey
import ir.vmessenger.core.ssh.SshAuth
import ir.vmessenger.core.ssh.SshTarget
import ir.vmessenger.domain.usecase.nodes.CompleteNodeProvisioningUseCase
import ir.vmessenger.domain.usecase.nodes.GetManagedNodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface NewNodeEvent {
    data class Edit(val form: NewNodeForm) : NewNodeEvent
    class KeyFile(val name: String, val bytes: ByteArray) : NewNodeEvent
    data object Next : NewNodeEvent
    data object Back : NewNodeEvent
    data object Start : NewNodeEvent
    data class HostKeyAnswer(val confirmed: Boolean) : NewNodeEvent
    data class SubmitSudo(val cancel: Boolean) : NewNodeEvent
    data class Decide(val allowed: Set<String>?) : NewNodeEvent
    data object Cancel : NewNodeEvent
    data object Finish : NewNodeEvent
    data object Retry : NewNodeEvent

    /** Leaving the wizard: a finished setup is forgotten; a running one is cancelled first by the screen. */
    data object Leave : NewNodeEvent
}

/**
 * The New node wizard. Secrets — the SSH password, the key file, its passphrase, the sudo password —
 * are held here in [TextFieldState]s and a byte array, never in [SavedStateHandle] or saveable UI
 * state; they are copied into the setup request as arrays when it starts and cleared here at once.
 * The saved state holds route arguments only.
 */
@HiltViewModel
class NewNodeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val session: NodeSetupSession,
    private val getManagedNode: GetManagedNodeUseCase,
    private val completeProvisioning: CompleteNodeProvisioningUseCase,
) : ViewModel() {
    private val formFlow = MutableStateFlow(NewNodeForm())
    val form: StateFlow<NewNodeForm> = formFlow.asStateFlow()
    val setup: StateFlow<NodeSetupState?> = session.state
    val question: StateFlow<SetupQuestion?> = session.question

    private val finishedFlow = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = finishedFlow.asStateFlow()

    private val finishFailedFlow = MutableStateFlow(false)
    val finishFailed: StateFlow<Boolean> = finishFailedFlow.asStateFlow()

    val password = TextFieldState()
    val passphrase = TextFieldState()
    val sudoPassword = TextFieldState()
    private var keyBytes: ByteArray? = null
    private var confirmedHostKey: HostKey? = null
    private var startedTarget: SshTarget? = null

    init {
        val managedId = savedStateHandle.get<String>(ARG_MANAGED_NODE)
        if (session.state.value != null) formFlow.update { it.copy(step = NewNodeStep.Install) }
        if (managedId != null) {
            viewModelScope.launch {
                getManagedNode(managedId)?.let { node ->
                    formFlow.update {
                        it.copy(
                            step = NewNodeStep.Server,
                            host = node.host,
                            port = node.sshPort.toString(),
                            user = node.sshUser,
                            address = if (node.domain != null) AddressKind.Domain else AddressKind.Ip,
                            domain = node.domain.orEmpty(),
                            secure = node.secured,
                            updating = node,
                        )
                    }
                }
            }
        }
    }

    fun onEvent(event: NewNodeEvent) {
        when (event) {
            is NewNodeEvent.Edit -> formFlow.value = event.form.copy(error = null)
            is NewNodeEvent.KeyFile -> {
                keyBytes?.fill(0)
                keyBytes = event.bytes
                formFlow.update { it.copy(keyName = event.name, error = null) }
            }
            NewNodeEvent.Next -> next()
            NewNodeEvent.Back -> back()
            NewNodeEvent.Start -> start()
            is NewNodeEvent.HostKeyAnswer, is NewNodeEvent.SubmitSudo, is NewNodeEvent.Decide -> answer(event)
            NewNodeEvent.Cancel -> session.cancel()
            NewNodeEvent.Finish -> finish()
            NewNodeEvent.Retry -> {
                session.reset()
                formFlow.update { it.copy(step = NewNodeStep.Server) }
            }
            NewNodeEvent.Leave -> if (!session.running) session.reset()
        }
    }

    private fun answer(event: NewNodeEvent) {
        when (event) {
            is NewNodeEvent.HostKeyAnswer -> {
                if (event.confirmed) confirmedHostKey = (question.value as? SetupQuestion.ConfirmHostKey)?.hostKey
                session.answerHostKey(event.confirmed)
            }
            is NewNodeEvent.SubmitSudo -> {
                session.answerSudoPassword(if (event.cancel) null else sudoPassword.chars())
                sudoPassword.clearText()
            }
            is NewNodeEvent.Decide -> session.answerDecisions(event.allowed)
            else -> Unit
        }
    }

    private fun next() {
        val f = formFlow.value
        val error = when (f.step) {
            NewNodeStep.Server -> serverError(f)
            NewNodeStep.Address -> addressError(f)
            else -> null
        }
        if (error != null) {
            formFlow.update { it.copy(error = error) }
            return
        }
        val next = NewNodeStep.entries.getOrNull(f.step.ordinal + 1) ?: return
        formFlow.update { it.copy(step = next, keyOnly = it.keyOnly && it.auth == AuthKind.Key) }
    }

    private fun back() {
        val previous = NewNodeStep.entries.getOrNull(formFlow.value.step.ordinal - 1) ?: return
        formFlow.update { it.copy(step = previous, error = null) }
    }

    private fun serverError(f: NewNodeForm): FormError? {
        val (host, user) = ServerInput.split(f.host, f.user)
        return when {
            !ServerInput.hostOk(host) -> FormError.Host
            ServerInput.port(f.port) == null -> FormError.Port
            !ServerInput.userOk(user) -> FormError.User
            f.auth == AuthKind.Password && password.text.isEmpty() -> FormError.Secret
            f.auth == AuthKind.Key && keyBytes == null -> FormError.Key
            else -> null
        }
    }

    private fun addressError(f: NewNodeForm): FormError? = when {
        f.publicPort.isNotBlank() && ServerInput.port(f.publicPort) == null -> FormError.PublicPort
        f.address == AddressKind.Domain && !ServerInput.domainOk(ServerInput.clean(f.domain)) -> FormError.Domain
        f.address == AddressKind.Domain && !ServerInput.emailOk(ServerInput.clean(f.email)) -> FormError.Email
        else -> null
    }

    private fun start() {
        val f = formFlow.value
        val (host, user) = ServerInput.split(f.host, f.user)
        val port = ServerInput.port(f.port) ?: return
        val auth = when (f.auth) {
            AuthKind.Password -> SshAuth.Password(password.chars())
            AuthKind.Key -> SshAuth.Key(keyBytes?.copyOf() ?: return, passphrase.chars().takeIf { it.isNotEmpty() })
        }
        val domain = ServerInput.clean(f.domain).takeIf { f.address == AddressKind.Domain }
        val email = ServerInput.clean(f.email).takeIf { domain != null && it.isNotEmpty() }
        val options = InstallOptions(
            publicHost = host,
            publicPort = ServerInput.port(f.publicPort) ?: InstallOptions.DEFAULT_PUBLIC_PORT,
            domain = domain,
            acmeEmail = email,
            secure = f.secure,
            keyOnlySsh = f.keyOnly && f.auth == AuthKind.Key,
            sshUser = user,
        )
        wipeInputs()
        formFlow.update { it.copy(step = NewNodeStep.Install) }
        val target = SshTarget(host, port, user).also { startedTarget = it }
        session.start(NodeSetupRequest(target, auth, options, knownHostKey = f.updating?.hostKey()))
    }

    private fun finish() {
        val done = session.state.value as? NodeSetupState.Done
        val f = formFlow.value
        val key = confirmedHostKey ?: f.updating?.hostKey()
        val target = startedTarget
        if (done == null || key == null || target == null) return
        val node = managedNodeOf(f, target, key, done.result, System.currentTimeMillis())
        viewModelScope.launch {
            val ok = completeProvisioning(node, f.useAsRelay) is AppResult.Success
            finishFailedFlow.value = !ok
            if (ok) {
                session.reset()
                finishedFlow.value = true
            }
        }
    }

    private fun wipeInputs() {
        password.clearText()
        passphrase.clearText()
        keyBytes?.fill(0)
        keyBytes = null
    }

    override fun onCleared() {
        wipeInputs()
        sudoPassword.clearText()
    }

    companion object {
        const val ARG_MANAGED_NODE = "managedNodeId"
    }
}

/** The field's text as a char array, without a String copy of it. */
private fun TextFieldState.chars(): CharArray = text.let { t -> CharArray(t.length) { t[it] } }

private fun ir.vmessenger.domain.model.ManagedNode.hostKey() = HostKey(hostKeyAlgorithm, hostKeyFingerprint)
