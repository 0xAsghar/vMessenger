plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.feature.lock"
}

dependencies {
    implementation(project(":data"))
    // Only for PinVerifier's length bounds: the keypad and the setup dialog have to enforce the
    // same minimum the verifier was designed around rather than pick their own.
    implementation(project(":core:crypto"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit)
}
