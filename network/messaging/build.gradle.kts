plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.network.messaging"
}

dependencies {
    implementation(project(":network:transport"))
    implementation(project(":network:discovery"))
    implementation(project(":core:crypto"))
    implementation(project(":core:proto"))
    implementation(project(":core:common"))
}
