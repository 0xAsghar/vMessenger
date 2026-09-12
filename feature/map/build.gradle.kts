plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.feature.map"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:location"))
    implementation(project(":core:map"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.collections.immutable)
}
