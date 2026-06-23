plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.network.bootstrap"
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:proto"))
    implementation(project(":core:common"))
}
