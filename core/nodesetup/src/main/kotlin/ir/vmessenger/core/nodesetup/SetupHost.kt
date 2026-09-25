package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.ssh.HostKey

/** The wizard's side of a setup: shows states and answers questions. */
interface SetupHost {
    fun onState(state: NodeSetupState)

    /** The person compares [key] with what their provider shows; nothing is sent before they say yes. */
    suspend fun confirmHostKey(key: HostKey): Boolean

    /** sudo wants a password; null cancels. [wrong]: the last one was refused. */
    suspend fun sudoPassword(wrong: Boolean): CharArray?

    /** The installer needs decisions (consent issues); returns the codes allowed, or null to cancel. */
    suspend fun decide(issues: List<Issue>): Set<String>?
}

/** Why a setup stopped; the engine turns it into [NodeSetupState.Failed]. */
class SetupStopped(val issue: Issue, val resumable: Boolean = false) : Exception(issue.code)

/** Waits between reconnects while following a run: 1, 2, 4 … 30 s, for up to ten minutes. */
class ReconnectPolicy(private val maxTotalMs: Long = TEN_MINUTES_MS) {
    fun delayMs(attempt: Int): Long = (FIRST_MS shl (attempt - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_MS)

    fun exhausted(attempt: Int): Boolean = (1..attempt).sumOf { delayMs(it) } > maxTotalMs

    private companion object {
        const val FIRST_MS = 1_000L
        const val MAX_MS = 30_000L
        const val MAX_SHIFT = 5
        const val TEN_MINUTES_MS = 600_000L
    }
}

/** A host key that changed since it was confirmed stops everything; nothing else about it is guessed. */
fun hostKeyChanged(known: HostKey?, seen: HostKey): Boolean = known != null && known != seen
