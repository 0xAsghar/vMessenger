plugins {
    alias(libs.plugins.vmessenger.jvm.library)
}

// SSH for "New node": sshj over BouncyCastle (Apache-2.0 and MIT). Pure JVM so the setup engine can
// run it in unit and end-to-end tests off-device; the app wires the BouncyCastle provider itself.
dependencies {
    api(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mina.sshd.core)
    testImplementation(libs.mina.sshd.sftp)
    testRuntimeOnly(libs.slf4j.simple)
}
