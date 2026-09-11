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
    implementation(libs.lazysodium.java)
    implementation(libs.jna)
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

// Deployable artifact: vmessenger-node-<version>.tar.gz, a gzip tarball of the
// installDist layout nested under vmessenger-node-<version>/ — extract with
// `tar -xzf … --strip-components=1 -C /opt/vmessenger`.
distributions {
    main {
        distributionBaseName.set("vmessenger-node")
    }
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

tasks.distZip {
    enabled = false
}

sourceSets {
    main {
        proto {
            srcDir("${rootProject.projectDir}/core/proto/src/main/proto")
        }
    }
}
