plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.core.crypto"
}

dependencies {
    implementation(project(":core:common"))
}
