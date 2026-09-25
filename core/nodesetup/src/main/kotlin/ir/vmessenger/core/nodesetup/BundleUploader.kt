package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.ssh.SshSession

/**
 * Puts the bundle in [dir] on the server: only files that are missing or differ (a re-run after a
 * dropped connection re-sends nothing it already has), then `sha256sum -c` over all of it.
 */
internal class BundleUploader(private val bundle: InstallerBundle, private val host: SetupHost) {

    suspend fun upload(session: SshSession, dir: String) {
        val quoted = ShellQuote.quote(dir)
        val present = io {
            session.run(
                "mkdir -p $quoted && cd $quoted && sha256sum ${bundle.files.joinToString(
                    " "
                ) { ShellQuote.quote(it.path) }} 2>/dev/null"
            )
        }.stdoutText.lines().mapNotNull { line ->
            line.split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 }?.let { it[1].trim() to it[0] }
        }.toMap()
        val missing = bundle.files.filter { present[it.path] != it.sha256 }
        val total = missing.sumOf { it.size }
        var done = 0L
        host.onState(NodeSetupState.Uploading(done, total))
        val dirs = missing.mapNotNull { it.path.substringBeforeLast('/', "").ifEmpty { null } }.distinct()
        if (dirs.isNotEmpty()) {
            io { session.run("cd $quoted && mkdir -p ${dirs.joinToString(" ") { ShellQuote.quote(it) }}") }
        }
        for (file in missing) {
            io { session.upload(file.open, file.size, "$dir/${file.path}") }
            done += file.size
            host.onState(NodeSetupState.Uploading(done, total))
        }
        val check = io { session.run("cd $quoted && sha256sum -c --quiet SHA256SUMS") }
        if (check.exitStatus != 0) {
            throw SetupStopped(
                Issue.of(IssueCode.UPLOAD_FAILED, detail = check.stdoutText.lines().firstOrNull().orEmpty())
            )
        }
    }
}
