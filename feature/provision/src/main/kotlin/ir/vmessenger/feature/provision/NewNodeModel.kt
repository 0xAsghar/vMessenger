package ir.vmessenger.feature.provision

import ir.vmessenger.core.nodesetup.InstallResult
import ir.vmessenger.core.ssh.HostKey
import ir.vmessenger.core.ssh.SshTarget
import ir.vmessenger.domain.model.ManagedNode
import ir.vmessenger.domain.model.ManagedNodeStatus
import ir.vmessenger.domain.model.ManagedNodeTls
import java.util.UUID

enum class NewNodeStep { Intro, Server, Address, Security, Review, Install }

enum class AuthKind { Password, Key }

enum class AddressKind { Ip, Domain }

enum class FormError { Host, Port, User, Secret, Key, Domain, Email, PublicPort }

/** Everything the wizard asks that is not a secret. Secrets live in the ViewModel's fields only. */
data class NewNodeForm(
    val step: NewNodeStep = NewNodeStep.Intro,
    val host: String = "",
    val port: String = "22",
    val user: String = "root",
    val auth: AuthKind = AuthKind.Password,
    val keyName: String? = null,
    val address: AddressKind = AddressKind.Ip,
    val domain: String = "",
    val email: String = "",
    /** Debug builds only: the port clients dial, for a test server whose 443 is mapped elsewhere. */
    val publicPort: String = "",
    val secure: Boolean = true,
    val keyOnly: Boolean = false,
    val useAsRelay: Boolean = true,
    val updating: ManagedNode? = null,
    val error: FormError? = null,
)

/** Server input as people type it, made into what SSH takes. */
object ServerInput {
    private val BIDI = Regex("[‎‏‪-‮⁦-⁩]")
    private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
    private val USER = Regex("^[a-z_][a-z0-9_.-]{0,31}$")
    private val DOMAIN = Regex("^(?=.{1,253}$)([A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,63}$")
    private val EMAIL = Regex("^[^\\s@'\"]+@[^\\s@'\"]+\\.[^\\s@'\"]+$")
    private const val MAX_PORT = 65_535

    /** Persian and Arabic-Indic digits to ASCII, bidi marks out, spaces trimmed. */
    fun clean(text: String): String = BIDI.replace(text, "").trim().map { ch ->
        when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰')
            in '٠'..'٩' -> '0' + (ch - '٠')
            else -> ch
        }
    }.joinToString("")

    /** `user@host` typed into the host field splits into both. */
    fun split(host: String, user: String): Pair<String, String> {
        val cleaned = clean(host).removePrefix("ssh://")
        val at = cleaned.lastIndexOf('@')
        return if (at > 0) cleaned.substring(at + 1) to cleaned.substring(0, at) else cleaned to clean(user)
    }

    fun hostOk(host: String) = HOST.matches(host)

    fun userOk(user: String) = USER.matches(user)

    fun port(text: String): Int? = clean(text).toIntOrNull()?.takeIf { it in 1..MAX_PORT }

    fun domainOk(domain: String) = DOMAIN.matches(domain)

    fun emailOk(email: String) = email.isEmpty() || EMAIL.matches(email)
}

/** The server the wizard just set up, as "Your servers" keeps it. */
fun managedNodeOf(form: NewNodeForm, target: SshTarget, hostKey: HostKey, result: InstallResult, now: Long) =
    ManagedNode(
        id = form.updating?.id ?: UUID.randomUUID().toString(),
        host = target.host,
        sshPort = target.port,
        sshUser = target.user,
        hostKeyAlgorithm = hostKey.algorithm,
        hostKeyFingerprint = hostKey.fingerprint,
        publicHost = result.publicHost,
        publicPort = result.publicPort,
        tls = when (result.mode) {
            "domain-ca" -> ManagedNodeTls.DOMAIN_CA
            "domain-pinned" -> ManagedNodeTls.DOMAIN_PINNED
            else -> ManagedNodeTls.IP_PINNED
        },
        domain = result.domain,
        relayUrl = result.relayUrl,
        bootstrapUrl = result.bootstrapUrl,
        nodeVersion = result.nodeVersion,
        nodeId = result.nodeId,
        secured = result.secured,
        keyOnlyLogin = result.keyOnlySsh == "applied",
        status = ManagedNodeStatus.READY,
        lastRunId = result.runId,
        createdAtUnixMs = form.updating?.createdAtUnixMs ?: now,
        updatedAtUnixMs = now,
    )
