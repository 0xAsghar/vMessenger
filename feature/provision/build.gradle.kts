plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.feature.provision"
}

// "New node": the wizard that sets a server up as a node over SSH. The engine and its contract
// types come from :core:nodesetup; the session that runs it is bound in :data.
dependencies {
    implementation(project(":domain"))
    implementation(project(":core:nodesetup"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
