plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
}

android {
    namespace = "ir.vmessenger.core.designsystem"
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.javax.inject)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    api(libs.coil.compose)
    api(libs.kotlinx.collections.immutable)
    testImplementation(libs.junit)
}
