plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.network.dht"
}

dependencies {
    implementation(project(":network:bootstrap"))
    implementation(project(":core:crypto"))
    implementation(project(":core:proto"))
    implementation(project(":core:common"))
}
