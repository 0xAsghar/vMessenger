import ir.vmessenger.convention.VerifyBytecodeLevelTask
import java.util.Properties

plugins {
    alias(libs.plugins.vmessenger.jvm.library)
    alias(libs.plugins.protobuf)
    application
}

group = "ir.vmessenger.node"

// The node ships with the same version as the app so a release tag maps to
// exactly one node tarball (vmessenger-node-<versionName>.tar.gz).
version = Properties().also { props: Properties ->
    rootProject.file("gradle/version.properties").inputStream().use { props.load(it) }
}.getProperty("versionName", "0.0.0")

application {
    mainClass.set("ir.vmessenger.node.NodeMain")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    // Ktor 3 logs through SLF4J 2; without a binding every request logs the NOP
    // warning and journald gets nothing. slf4j-simple writes to stdout.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// The tests run on the oldest JRE a server may give the node, so what they prove — Ed25519 on the
// JDK's own provider included — holds on Debian 12's Java 17.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(JAVA_17)) })
}

// Deployable artifact: vmessenger-node-<version>.tar.gz, a gzip tarball of the
// installDist layout nested under vmessenger-node-<version>/ — extract with
// `tar -xzf … --strip-components=1 -C /opt/vmessenger`.
//
// The tarball carries a VERSION file so an installer can tell what it is about to put down without
// running it, and no .bat launcher: the node only runs on Linux.
val nodeVersionFile = tasks.register("nodeVersionFile") {
    val versionText = project.version.toString()
    val out = layout.buildDirectory.file("generated/node-version/VERSION")
    inputs.property("version", versionText)
    outputs.file(out)
    doLast { out.get().asFile.writeText(versionText + "\n") }
}

distributions {
    main {
        distributionBaseName.set("vmessenger-node")
        contents {
            from(nodeVersionFile)
            exclude("**/*.bat")
        }
    }
}

// Every class the node loads — ours and every dependency's — must run on Java 17 (see
// configureKotlinJvm). A single class built for 21 would only show up as a start failure on a
// Debian 12 server, so the build refuses to package one.
val verifyBytecodeLevel = tasks.register<VerifyBytecodeLevelTask>("verifyBytecodeLevel") {
    classpath.from(tasks.jar, configurations.runtimeClasspath)
    maxMajor.set(JAVA_17_CLASS_MAJOR)
    report.set(layout.buildDirectory.file("reports/bytecode-level.txt"))
}

listOf("distTar", "installDist").forEach { name ->
    tasks.named(name) { dependsOn(verifyBytecodeLevel) }
}

tasks.named("check") { dependsOn(verifyBytecodeLevel) }

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

tasks.distZip {
    enabled = false
}

// What the app bundles (app/build.gradle.kts, BundleNodeInstallerTask): the distribution tarball.
val nodeDistTar by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(nodeDistTar.name, tasks.distTar)
}

sourceSets {
    main {
        proto {
            srcDir("${rootProject.projectDir}/core/proto/src/main/proto")
        }
    }
}

val JAVA_17 = 17
val JAVA_17_CLASS_MAJOR = 61
