plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
}

android {
    namespace = "ir.vmessenger.feature.location"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:location"))
}
