plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.compose)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.feature.chat"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // ActiveConversationTracker: suppresses notifications for the open chat.
    implementation(project(":core:notifications"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    // Declared even though :core:designsystem exposes it: this module builds its own
    // Coil requests (the attachment fetcher), so the dependency is direct, not borrowed.
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.collections.immutable)
}
