plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.core.location"
}

dependencies {
    implementation(project(":core:common"))
}
