plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.core.storage"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
}
