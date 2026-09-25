plugins {
    alias(libs.plugins.vmessenger.jvm.library)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    // Pinned TLS is tested against a real TLS server with generated certificates.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
