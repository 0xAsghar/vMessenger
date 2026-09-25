package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.ssh.HostKey
import ir.vmessenger.core.ssh.SshAuth
import ir.vmessenger.core.ssh.SshConnector
import ir.vmessenger.core.ssh.SshException
import ir.vmessenger.core.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sets a server up as a node (docs/Deployment.md §8): host key first, then login, privilege, the
 * bundle, preflight and its decisions, the detached run followed to its end (reconnecting and
 * resuming as needed), key-only SSH confirmed by a fresh key login, result.json checked, and the
 * phone's own look at the node. The request's credentials are wiped when this returns, however it
 * ends; nothing here stores anything.
 */
class NodeSetupEngine(
    private val connector: SshConnector,
    private val bundle: InstallerBundle,
    private val verifier: NodeVerifier,
    private val now: () -> Long = System::currentTimeMillis,
    private val reconnect: ReconnectPolicy = ReconnectPolicy(),
) {
    suspend fun run(request: NodeSetupRequest, host: SetupHost): NodeSetupState {
        val end = try {
            setUp(request, host)
        } catch (stopped: SetupStopped) {
            NodeSetupState.Failed(stopped.issue, logTail = emptyList(), resumable = stopped.resumable)
        } catch (e: SshException) {
            NodeSetupState.Failed(issueOf(e), logTail = emptyList(), resumable = true)
        } finally {
            request.auth.wipe()
        }
        host.onState(end)
        return end
    }

    private suspend fun setUp(request: NodeSetupRequest, host: SetupHost): NodeSetupState {
        if (bundle.protocol != INSTALLER_PROTOCOL) stop(IssueCode.PROTOCOL_MISMATCH)
        host.onState(NodeSetupState.Connecting)
        val key = io { connector.probeHostKey(request.target) }
        if (hostKeyChanged(request.knownHostKey, key)) stop(IssueCode.SSH_HOST_KEY_MISMATCH, key.fingerprint)
        host.onState(NodeSetupState.ConfirmHostKey(key))
        if (!host.confirmHostKey(key)) stop(IssueCode.CANCELLED)
        val login: suspend () -> SshSession = { io { connector.connect(request.target, request.auth, key) } }
        val session = login()
        host.onState(NodeSetupState.CheckingServer)
        val probe = io { session.run(ServerProbe.COMMAND) }.let { ServerProbe.parse(it.stdoutText) }
            ?: stop(IssueCode.INTERNAL, "the server's shell did not answer the probe")
        val shell =
            PrivilegedShell(
                session,
                sudoFor(session, probe, host),
                "${probe.home}/.vmessenger-installer/${bundle.nodeVersion}"
            )
        BundleUploader(bundle, host).upload(session, shell.bundleDir)
        val installer = InstallerRun(shell, request, host, now() - probe.serverTimeMs, reconnect) {
            login().also { shell.session = it }
        }
        val result = installer.install(bundle.nodeVersion, keyLogin(request, key))
        host.onState(NodeSetupState.Verifying)
        return NodeSetupState.Done(result, verifier.verify(result))
    }

    /** A fresh login with the key alone, for the installer's key-only SSH check; null for passwords. */
    private fun keyLogin(request: NodeSetupRequest, key: HostKey): (suspend () -> SshSession)? =
        (request.auth as? SshAuth.Key)?.let { auth ->
            {
                val copy = SshAuth.Key(auth.privateKey.copyOf(), auth.passphrase?.copyOf())
                io { connector.connect(request.target, copy, key) }
            }
        }

    private suspend fun sudoFor(session: SshSession, probe: ServerProbe, host: SetupHost): Sudo = when {
        probe.uid == 0 -> Sudo.Root
        probe.sudoWithoutPassword -> Sudo.NoPassword
        else -> askSudoPassword(session, host)
    }

    private suspend fun askSudoPassword(session: SshSession, host: SetupHost): Sudo {
        var wrong = false
        repeat(SUDO_TRIES) {
            host.onState(NodeSetupState.NeedsSudoPassword)
            val password = host.sudoPassword(wrong) ?: stop(IssueCode.CANCELLED)
            val check = PrivilegedShell(session, Sudo.WithPassword(password), "")
            val result = io { check.run("sudo -S -p '' -k true") }
            if (result.exitStatus == 0) return Sudo.WithPassword(password)
            if (NOT_ALLOWED.containsMatchIn(result.stderrText)) stop(IssueCode.SUDO_NOT_ALLOWED)
            password.fill('\u0000')
            wrong = true
        }
        stop(IssueCode.SUDO_PASSWORD_WRONG)
    }

    private companion object {
        const val SUDO_TRIES = 3
        val NOT_ALLOWED = Regex("not in the sudoers|is not allowed to|may not run sudo")
    }
}

internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** Ends the setup with [code]. */
internal fun stop(code: IssueCode, detail: String = "", resumable: Boolean = false): Nothing =
    throw SetupStopped(Issue.of(code, detail = detail), resumable)

internal fun issueOf(e: SshException): Issue = when (e) {
    is SshException.Unreachable -> Issue.of(IssueCode.SSH_UNREACHABLE)
    is SshException.Timeout -> Issue.of(IssueCode.SSH_TIMEOUT)
    is SshException.HostKeyMismatch -> Issue.of(
        IssueCode.SSH_HOST_KEY_MISMATCH,
        detail = e.actual?.fingerprint.orEmpty()
    )
    is SshException.AuthRejected -> Issue.of(IssueCode.SSH_AUTH_REJECTED, detail = e.allowedMethods.joinToString(","))
    is SshException.KeyUnreadable -> Issue.of(IssueCode.SSH_KEY_UNREADABLE)
    is SshException.Disconnected -> Issue.of(IssueCode.SSH_DISCONNECTED)
}
