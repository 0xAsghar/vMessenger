plugins {
    alias(libs.plugins.vmessenger.jvm.library)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
