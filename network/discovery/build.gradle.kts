plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.network.discovery"
}

dependencies {
    implementation(project(":network:dht"))
    implementation(project(":core:crypto"))
    implementation(project(":core:proto"))
    implementation(project(":core:common"))
}
