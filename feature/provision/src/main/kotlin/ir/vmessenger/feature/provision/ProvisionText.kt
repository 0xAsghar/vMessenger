package ir.vmessenger.feature.provision

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.nodesetup.Issue
import ir.vmessenger.core.nodesetup.IssueCode
import ir.vmessenger.core.nodesetup.StepId

@Composable
internal fun stepLabel(id: StepId): String = stringResource(STEP_LABELS.getValue(id))

private val STEP_LABELS = mapOf(
    StepId.PREFLIGHT to R.string.provision_step_preflight,
    StepId.APT to R.string.provision_step_apt,
    StepId.SWAP to R.string.provision_step_swap,
    StepId.JAVA to R.string.provision_step_java,
    StepId.PACKAGES to R.string.provision_step_packages,
    StepId.PORTS to R.string.provision_step_ports,
    StepId.FIREWALL to R.string.provision_step_firewall,
    StepId.FILES to R.string.provision_step_files,
    StepId.TLS to R.string.provision_step_tls,
    StepId.CONFIG to R.string.provision_step_config,
    StepId.SERVICE to R.string.provision_step_service,
    StepId.HEALTH to R.string.provision_step_health,
    StepId.FAIL2BAN to R.string.provision_step_fail2ban,
    StepId.UPDATES to R.string.provision_step_updates,
    StepId.TIMESYNC to R.string.provision_step_timesync,
    StepId.SSH to R.string.provision_step_ssh,
    StepId.FINISH to R.string.provision_step_finish,
    StepId.UNINSTALL to R.string.provision_step_uninstall,
)

/** An issue in words, with its code (and the server's detail) after it for anyone who asks for help. */
@Composable
internal fun issueText(issue: Issue): String {
    val text = stringResource(ProvisionIssueText.of(issue.known))
    val detail = issue.detail.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
    return "$text\n⁦${issue.code}$detail⁩"
}

/** Every issue code has words: the specific ones first, then each family's. */
internal object ProvisionIssueText {
    @StringRes
    fun of(code: IssueCode?): Int = code?.let { specific[it] ?: family(it) } ?: R.string.provision_issue_unknown

    private val specific = mapOf(
        IssueCode.SSH_UNREACHABLE to R.string.provision_issue_ssh_unreachable,
        IssueCode.SSH_TIMEOUT to R.string.provision_issue_ssh_unreachable,
        IssueCode.SSH_AUTH_REJECTED to R.string.provision_issue_ssh_auth,
        IssueCode.SSH_KEY_UNREADABLE to R.string.provision_issue_ssh_key,
        IssueCode.SSH_HOST_KEY_MISMATCH to R.string.provision_issue_host_key_changed,
        IssueCode.SSH_DISCONNECTED to R.string.provision_issue_disconnected,
        IssueCode.SUDO_PASSWORD_WRONG to R.string.provision_issue_sudo_wrong,
        IssueCode.SUDO_NOT_ALLOWED to R.string.provision_issue_sudo_not_allowed,
        IssueCode.NOT_ROOT to R.string.provision_issue_sudo_not_allowed,
        IssueCode.OS_UNSUPPORTED to R.string.provision_issue_os_unsupported,
        IssueCode.ARCH_UNSUPPORTED to R.string.provision_issue_os_unsupported,
        IssueCode.NO_SYSTEMD to R.string.provision_issue_os_unsupported,
        IssueCode.OS_UNTESTED to R.string.provision_issue_os_untested,
        IssueCode.OS_EOL to R.string.provision_issue_os_eol,
        IssueCode.RAM_TOO_LOW to R.string.provision_issue_ram,
        IssueCode.DISK_LOW to R.string.provision_issue_disk,
        IssueCode.CLOCK_SKEW to R.string.provision_issue_clock,
        IssueCode.PORT_APACHE to R.string.provision_issue_apache,
        IssueCode.PUBLIC_PORT_TAKEN to R.string.provision_issue_port_taken,
        IssueCode.DOWNGRADE to R.string.provision_issue_downgrade,
        IssueCode.INSTALL_BUSY to R.string.provision_issue_busy,
        IssueCode.DOMAIN_NOT_HERE to R.string.provision_issue_domain_elsewhere,
        IssueCode.LE_RATE_LIMITED to R.string.provision_issue_le,
        IssueCode.REACH_TIMEOUT to R.string.provision_issue_reach_timeout,
        IssueCode.REACH_REFUSED to R.string.provision_issue_reach_timeout,
        IssueCode.REACH_TLS_MISMATCH to R.string.provision_issue_reach_tls,
        IssueCode.HARDEN_SSH_ROLLED_BACK to R.string.provision_issue_key_only_rolled_back,
        IssueCode.CANCELLED to R.string.provision_issue_cancelled,
        IssueCode.PROTOCOL_MISMATCH to R.string.provision_issue_internal,
        IssueCode.RESULT_INVALID to R.string.provision_issue_internal,
    )

    private val families = listOf(
        listOf("APT_", "DPKG_", "JAVA_") to R.string.provision_issue_apt,
        listOf("SWAP_", "DISK_", "CLOCK_") to R.string.provision_issue_fixed,
        listOf("PORT_", "HTTP_", "NODE_PORT", "UFW_") to R.string.provision_issue_ports,
        listOf("NGINX_") to R.string.provision_issue_nginx,
        listOf("TLS_", "ACME_", "LE_") to R.string.provision_issue_le,
        listOf("HEALTH_", "RELAY_", "ADVERTISED_") to R.string.provision_issue_health,
        listOf("HARDEN_") to R.string.provision_issue_harden,
        listOf("BUNDLE_", "UPLOAD_") to R.string.provision_issue_upload,
    )

    private fun family(code: IssueCode): Int =
        families.firstOrNull { (prefixes, _) -> prefixes.any(code.name::startsWith) }?.second
            ?: R.string.provision_issue_internal
}
