plugins {
    alias(libs.plugins.vmessenger.jvm.library)
}

// The "New node" engine: drives scripts/setup-node.sh on a server over SSH (docs/Deployment.md §8).
// Pure JVM — unit-tested against a scripted fake session, and run end to end against the Docker
// targets of scripts/provision-test (provisionE2e).
dependencies {
    api(project(":core:ssh"))
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}

// The end-to-end run against a Docker target; scripts/provision-test/run.sh passes the target.
tasks.test {
    filter { excludeTestsMatching("*ProvisionE2eTest") }
}

tasks.register<Test>("provisionE2e") {
    description = "Sets a node up on a scripts/provision-test target with the real engine and SSH"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*ProvisionE2eTest") }
    listOf("vmE2eTarget", "vmE2eBundle", "vmE2eKey", "vmE2eHttpsPort").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
