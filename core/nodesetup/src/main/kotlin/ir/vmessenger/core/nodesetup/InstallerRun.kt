package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.ssh.SshException
import ir.vmessenger.core.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One install through the installer's machine mode: preflight until nothing needs deciding, launch,
 * follow the detached run to its end, and read its result. A decision the run itself stops for
 * (exit 10) is asked and the run launched again.
 */
internal class InstallerRun(
    private val shell: PrivilegedShell,
    private val request: NodeSetupRequest,
    private val host: SetupHost,
    private val clockOffsetMs: Long,
    private val reconnect: ReconnectPolicy,
    private val relogin: suspend () -> SshSession,
) {
    private val allowed = mutableSetOf<String>()
    private val progress = RunProgress(host)

    suspend fun install(nodeVersion: String, keyLogin: (suspend () -> SshSession)?): InstallResult {
        var runId = preflight()
        repeat(MAX_RELAUNCHES) {
            val id = runId ?: launch()
            val status = follow(id, keyLogin)
            when (status) {
                0 -> return result(id, nodeVersion)
                DECISION -> {
                    decide(progress.consents())
                    runId = null
                }
                else -> throw SetupStopped(progress.fatal() ?: Issue.of(IssueCode.INTERNAL), resumable = true)
            }
        }
        throw SetupStopped(Issue.of(IssueCode.INTERNAL, detail = "too many decisions"), resumable = true)
    }

    /** Preflight until it passes; a run already in progress (INSTALL_BUSY) is joined, not replaced. */
    private suspend fun preflight(): String? {
        repeat(MAX_RELAUNCHES) {
            val result = io {
                shell.run(
                    shell.installer(listOf("--bundle-dir", shell.bundleDir, "--preflight") + args())
                )
            }
            val markers = result.stdoutText.lines().mapNotNull(MarkerParser::parse)
            val issues = markers.mapNotNull(MarkerParser::issueOf)
            when (result.exitStatus) {
                0 -> return null
                DECISION -> decide(issues.filter { it.severity == Severity.CONSENT })
                BUSY -> return markers.firstOrNull { it.event == "fact" && it["key"] == "active_run" }?.get("value")
                else -> throw SetupStopped(
                    issues.lastOrNull { it.severity == Severity.FATAL } ?: Issue.of(IssueCode.INTERNAL)
                )
            }
        }
        throw SetupStopped(Issue.of(IssueCode.INTERNAL, detail = "preflight keeps asking"))
    }

    private suspend fun decide(issues: List<Issue>) {
        host.onState(NodeSetupState.NeedsDecision(issues))
        allowed += host.decide(issues) ?: throw SetupStopped(Issue.of(IssueCode.CANCELLED))
    }

    private suspend fun launch(): String {
        val result = io { shell.run(shell.installer(listOf("--bundle-dir", shell.bundleDir, "--launch") + args())) }
        val markers = result.stdoutText.lines().mapNotNull(MarkerParser::parse)
        val failure = markers.mapNotNull(MarkerParser::issueOf).lastOrNull() ?: Issue.of(IssueCode.RUN_START_FAILED)
        return markers.firstOrNull { it.event == "launched" }?.get("run") ?: throw SetupStopped(failure)
    }

    private fun args(): List<String> = request.options.toArgs(allowed, clockOffsetMs)

    /**
     * Streams the run's log until it ends. A dropped connection is logged in again and the log
     * picked up from the end of the last whole line; the run itself never noticed.
     */
    private suspend fun follow(runId: String, keyLogin: (suspend () -> SshSession)?): Int {
        val lines = LogLineAssembler()
        var attempt = 0
        while (true) {
            try {
                return coroutineScope {
                    io {
                        shell.stream(
                            shell.installer(listOf("--follow", runId, "--from-byte", lines.offset.toString()))
                        ) { chunk ->
                            lines.feed(chunk).forEach { line -> onLine(line, runId, keyLogin, this) }
                        }
                    }
                }
            } catch (e: SshException.AuthRejected) {
                throw e
            } catch (e: SshException) {
                attempt++
                if (reconnect.exhausted(attempt)) throw e
                host.onState(NodeSetupState.Reconnecting(attempt))
                delay(reconnect.delayMs(attempt))
                runCatching { relogin() }
            }
        }
    }

    private fun onLine(line: String, runId: String, keyLogin: (suspend () -> SshSession)?, scope: CoroutineScope) {
        val marker = progress.onLine(line) ?: return
        if (marker.event == "fact" && marker["key"] == "ssh_confirm" && keyLogin != null) {
            // The run waits for proof that a key login works before keeping password logins off.
            scope.launch {
                runCatching {
                    keyLogin().use { fresh ->
                        val confirm = PrivilegedShell(fresh, shell.sudoMode, shell.bundleDir)
                        confirm.run(confirm.installer(listOf("--confirm-ssh", runId)))
                    }
                }
            }
        }
    }

    private suspend fun result(runId: String, nodeVersion: String): InstallResult {
        val json = io { shell.run(shell.installer(listOf("--result", runId))) }.stdoutText
        return InstallResult.parse(json, nodeVersion)
            ?: throw SetupStopped(Issue.of(IssueCode.RESULT_INVALID, detail = json.take(DETAIL_CHARS)))
    }

    private companion object {
        const val DECISION = 10
        const val BUSY = 40
        const val MAX_RELAUNCHES = 4
        const val DETAIL_CHARS = 300
    }
}

/** What the run has reported so far: steps, issues, the log's tail. */
internal class RunProgress(private val host: SetupHost) {
    private val steps = linkedMapOf<StepId, StepState>()
    private val issues = mutableListOf<Issue>()
    private val tail = ArrayDeque<String>()
    private var lastSeq = 0L

    /** Takes one log line; returns its marker, if it is one (and not seen before). */
    fun onLine(line: String): Marker? {
        val marker = MarkerParser.parse(line)
        val fresh = marker?.takeIf { it.event == "hello" || it.seq > lastSeq }
        when {
            marker == null -> {
                tail.addLast(line)
                while (tail.size > TAIL_LINES) tail.removeFirst()
            }
            fresh != null -> record(fresh)
        }
        return fresh
    }

    private fun record(marker: Marker) {
        lastSeq = marker.seq
        when (marker.event) {
            "step" -> {
                val id = StepId.of(marker["id"].orEmpty())
                val state = StepState.entries.firstOrNull { it.name.equals(marker["state"], ignoreCase = true) }
                if (id != null && state != null) steps[id] = state
            }
            "issue" -> MarkerParser.issueOf(marker)?.let(issues::add)
        }
        host.onState(NodeSetupState.Installing(steps.toMap(), issues.toList(), tail.toList()))
    }

    fun consents(): List<Issue> = issues.filter { it.severity == Severity.CONSENT }

    fun fatal(): Issue? = issues.lastOrNull { it.severity == Severity.FATAL }

    private companion object {
        const val TAIL_LINES = 200
    }
}
