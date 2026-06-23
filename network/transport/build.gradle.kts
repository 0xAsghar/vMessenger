plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.network.transport"
}

dependencies {
    implementation(project(":core:common"))
}
