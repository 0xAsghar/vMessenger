package ir.vmessenger.data.nodesetup

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.nodesetup.Issue
import ir.vmessenger.core.nodesetup.IssueCode
import ir.vmessenger.core.nodesetup.NodeSetupEngine
import ir.vmessenger.core.nodesetup.NodeSetupRequest
import ir.vmessenger.core.nodesetup.NodeSetupSession
import ir.vmessenger.core.nodesetup.NodeSetupState
import ir.vmessenger.core.nodesetup.SetupHost
import ir.vmessenger.core.nodesetup.SetupQuestion
import ir.vmessenger.core.ssh.HostKey
import ir.vmessenger.core.ssh.SshjConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs "New node" setups, one at a time, outside any screen: a setup survives rotation and the
 * wizard being left and reopened. The credentials live in the request only, in memory; the engine
 * wipes them when it returns — done, failed, cancelled — and a question left unanswered for ten
 * minutes ends the setup, so a forgotten phone does not keep a server password around.
 *
 * Nothing here is logged but state names and issue codes.
 */
@Singleton
class NodeSetupController @Inject constructor(
    private val bundle: AssetInstallerBundle,
    private val verifier: PinnedNodeVerifier,
) : NodeSetupSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFlow = MutableStateFlow<NodeSetupState?>(null)
    private val questionFlow = MutableStateFlow<SetupQuestion?>(null)
    private var job: Job? = null

    @Volatile
    private var pending: CompletableDeferred<Any?>? = null

    override val state: StateFlow<NodeSetupState?> = stateFlow.asStateFlow()
    override val question: StateFlow<SetupQuestion?> = questionFlow.asStateFlow()
    override val running: Boolean get() = job?.isActive == true

    override fun start(request: NodeSetupRequest) {
        if (running) {
            request.auth.wipe()
            return
        }
        SshCryptoProvider.ensureInstalled()
        stateFlow.value = NodeSetupState.Connecting
        val engine = NodeSetupEngine(SshjConnector(), bundle, verifier)
        job = scope.launch {
            val end = engine.run(request, host)
            val code = (end as? NodeSetupState.Failed)?.issue?.code?.let { " $it" }.orEmpty()
            AppLogger.info(TAG, "setup ended: ${end.javaClass.simpleName}$code")
        }
    }

    override fun answerHostKey(confirmed: Boolean) {
        pending?.complete(confirmed)
    }

    override fun answerSudoPassword(password: CharArray?) {
        if (pending?.complete(password) != true) password?.fill('\u0000')
    }

    override fun answerDecisions(allowed: Set<String>?) {
        pending?.complete(allowed)
    }

    override fun cancel() {
        pending?.complete(null)
        job?.cancel()
        stateFlow.value = NodeSetupState.Failed(Issue.of(IssueCode.CANCELLED), emptyList(), resumable = false)
    }

    override fun reset() {
        if (!running) {
            stateFlow.value = null
            questionFlow.value = null
        }
    }

    private val host = object : SetupHost {
        override fun onState(state: NodeSetupState) {
            if (stateFlow.value?.javaClass != state.javaClass) {
                AppLogger.info(TAG, "setup: ${state.javaClass.simpleName}")
            }
            stateFlow.value = state
        }

        override suspend fun confirmHostKey(key: HostKey): Boolean =
            ask(SetupQuestion.ConfirmHostKey(key)) as? Boolean ?: false

        override suspend fun sudoPassword(wrong: Boolean): CharArray? = ask(
            SetupQuestion.SudoPassword(wrong)
        ) as? CharArray

        @Suppress("UNCHECKED_CAST")
        override suspend fun decide(issues: List<ir.vmessenger.core.nodesetup.Issue>): Set<String>? =
            ask(SetupQuestion.Decisions(issues)) as? Set<String>
    }

    private suspend fun ask(question: SetupQuestion): Any? {
        val answer = CompletableDeferred<Any?>()
        pending = answer
        questionFlow.value = question
        return try {
            withTimeoutOrNull(QUESTION_TIMEOUT_MS) { answer.await() }
        } finally {
            questionFlow.value = null
            pending = null
        }
    }

    private companion object {
        const val TAG = "NodeSetup"
        const val QUESTION_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
