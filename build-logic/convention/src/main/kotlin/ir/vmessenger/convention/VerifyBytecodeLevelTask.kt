package ir.vmessenger.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Fails when any class the node would load is newer than [maxMajor] (61 = Java 17).
 *
 * The node runs on whatever JRE the server's apt offers, and Debian 12 has no Java 21: one class
 * compiled for 21 — ours or a dependency's — is an `UnsupportedClassVersionError` at start, long
 * after the build passed. Multi-release entries under `META-INF/versions/` are skipped, because an
 * older JRE never loads them.
 */
@CacheableTask
abstract class VerifyBytecodeLevelTask : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val maxMajor: Property<Int>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val limit = maxMajor.get()
        val offenders = mutableListOf<String>()
        var checked = 0
        classpath.files.filter { it.exists() }.forEach { root ->
            classEntries(root) { name, major ->
                checked++
                if (major > limit) offenders += "${root.name}!$name (major $major)"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "${offenders.size} class(es) need a JRE newer than major $limit:\n" +
                    offenders.take(MAX_LISTED).joinToString("\n"),
            )
        }
        report.get().asFile.writeText("checked $checked classes, all at or below major $limit\n")
    }

    private fun classEntries(root: File, onClass: (String, Int) -> Unit) {
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(CLASS) }.forEach { file ->
                file.inputStream().use { onClass(file.relativeTo(root).path, majorOf(it)) }
            }
        } else if (root.name.endsWith(".jar")) {
            ZipFile(root).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(CLASS) && !it.name.startsWith(VERSIONED) }
                    .forEach { entry -> zip.getInputStream(entry).use { onClass(entry.name, majorOf(it)) } }
            }
        }
    }

    /** Bytes 0–3 are the magic, 4–5 the minor version, 6–7 the major. */
    private fun majorOf(input: InputStream): Int = DataInputStream(input).run {
        readInt()
        readUnsignedShort()
        readUnsignedShort()
    }

    private companion object {
        const val CLASS = ".class"
        const val VERSIONED = "META-INF/versions/"
        const val MAX_LISTED = 20
    }
}
