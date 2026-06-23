plugins {
    alias(libs.plugins.vmessenger.jvm.library)
}

dependencies {
    implementation(project(":core:common"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
