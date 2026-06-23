plugins {
    alias(libs.plugins.vmessenger.jvm.library)
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
