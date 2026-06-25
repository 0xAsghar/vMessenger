plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.feature.debug"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
}
