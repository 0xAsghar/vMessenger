plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
}

android {
    namespace = "ir.vmessenger.core.designsystem"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
