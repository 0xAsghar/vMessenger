plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
}

android {
    namespace = "ir.vmessenger.core.map"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    api(project(":core:designsystem"))
    api(project(":core:location"))
    api(libs.maplibre.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)
}
