package ir.vmessenger.core.nodesetup

/** POSIX single-quoting: safe for any text, including quotes and `$(…)`. */
object ShellQuote {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }
}

/** What to install, from the wizard's choices. Validated before any of it reaches a shell. */
data class InstallOptions(
    /** What clients dial: the address or name the phone reached the server at. */
    val publicHost: String,
    val publicPort: Int = 443,
    val domain: String? = null,
    val acmeEmail: String? = null,
    val secure: Boolean = true,
    val keyOnlySsh: Boolean = false,
    val sshUser: String,
) {
    init {
        require(HOST.matches(publicHost)) { "not a host" }
        require(publicPort in 1..MAX_PORT) { "not a port" }
        require(domain == null || DOMAIN.matches(domain)) { "not a domain" }
        require(acmeEmail == null || EMAIL.matches(acmeEmail)) { "not an e-mail address" }
        require(USER.matches(sshUser)) { "not a user name" }
    }

    /** The installer's install options, plus what has been allowed and the measured clock offset. */
    fun toArgs(allowed: Set<String>, clockOffsetMs: Long?): List<String> = buildList {
        add("--public-host")
        add(publicHost)
        add("--public-port")
        add(publicPort.toString())
        if (domain != null) {
            addAll(listOf("--domain", domain))
            if (acmeEmail != null) addAll(listOf("--acme-email", acmeEmail)) else add("--acme-no-email")
        }
        if (secure) add("--secure")
        if (keyOnlySsh) add("--key-only-ssh")
        addAll(listOf("--ssh-user", sshUser))
        if (allowed.isNotEmpty()) addAll(listOf("--allow", allowed.sorted().joinToString(",")))
        if (clockOffsetMs != null) addAll(listOf("--clock-offset-ms", clockOffsetMs.toString()))
    }

    private companion object {
        const val MAX_PORT = 65_535
        val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        val DOMAIN = Regex("^(?=.{1,253}$)([A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,63}$")
        val EMAIL = Regex("^[^\\s@'\"]+@[^\\s@'\"]+\\.[^\\s@'\"]+$")
        val USER = Regex("^[a-z_][a-z0-9_.-]{0,31}$")
    }
}
