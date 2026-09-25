package ir.vmessenger.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * The node installer the app uploads to a server (docs/Deployment.md §8), as APK assets under
 * `node-installer/`: `setup-node.sh`, `deploy/`, `vmessenger-node-<version>.tgz`, `manifest.json`
 * and `SHA256SUMS`. Nothing is fetched at install time, so this is everything.
 *
 * `.tgz`, not `.tar.gz`: Android's asset packager gunzips any asset ending in `.gz`, which would
 * ship a raw tar twice the size and break the checksums.
 *
 * Refuses a tarball whose version is not the app's, and a script without the machine-mode protocol
 * the app speaks.
 */
@CacheableTask
abstract class BundleNodeInstallerTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val installer: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val deployDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val nodeTarball: ConfigurableFileCollection

    @get:Input
    abstract val nodeVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val version = nodeVersion.get()
        val tarball = nodeTarball.singleFile
        if (tarball.name != "vmessenger-node-$version.tar.gz") {
            throw GradleException("node tarball ${tarball.name} is not version $version")
        }
        val script = installer.get().asFile
        val protocol = PROTOCOL.find(script.readText())?.groupValues?.get(1)
            ?: throw GradleException("${script.name} declares no PROTOCOL_VERSION")
        val root = outputDir.get().asFile.also { it.deleteRecursively() }
        val bundle = File(root, "node-installer").apply { mkdirs() }
        script.copyTo(File(bundle, "setup-node.sh"))
        deployDir.get().asFile.copyRecursively(File(bundle, "deploy"))
        tarball.copyTo(File(bundle, "vmessenger-node-$version.tgz"))
        File(bundle, "manifest.json").writeText("{\"protocol\": $protocol, \"nodeVersion\": \"$version\"}\n")
        val sums = bundle.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(bundle).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .joinToString("") { (path, file) -> "${sha256(file)}  $path\n" }
        File(bundle, "SHA256SUMS").writeText(sums)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val PROTOCOL = Regex("""(?m)^readonly PROTOCOL_VERSION=(\d+)$""")
        const val BUFFER_SIZE = 64 * 1024
    }
}
