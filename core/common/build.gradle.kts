plugins {
    alias(libs.plugins.vmessenger.jvm.library)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
