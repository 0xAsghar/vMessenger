plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.core.notifications"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
}
