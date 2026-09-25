package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.ssh.HostKey
import kotlinx.coroutines.flow.StateFlow

/** A question the setup is waiting on; the screen answers it through [NodeSetupSession]. */
sealed class SetupQuestion {
    data class ConfirmHostKey(val hostKey: HostKey) : SetupQuestion()
    data class SudoPassword(val wrong: Boolean) : SetupQuestion()
    data class Decisions(val issues: List<Issue>) : SetupQuestion()
}

/**
 * The one setup this process runs at a time (implemented in `:data`, which owns the engine and the
 * credentials; they live in memory only, and are wiped when the setup ends however it ends).
 */
interface NodeSetupSession {
    /** Null before a setup starts and after [reset]. */
    val state: StateFlow<NodeSetupState?>

    val question: StateFlow<SetupQuestion?>

    val running: Boolean

    fun start(request: NodeSetupRequest)

    fun answerHostKey(confirmed: Boolean)

    /** The array is handed over: the session wipes it when the setup ends. Null cancels. */
    fun answerSudoPassword(password: CharArray?)

    fun answerDecisions(allowed: Set<String>?)

    fun cancel()

    /** Forgets a finished setup's state, so the next screen starts clean. */
    fun reset()
}
