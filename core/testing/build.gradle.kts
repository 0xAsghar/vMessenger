plugins {
    alias(libs.plugins.vmessenger.android.library)
}

android {
    namespace = "ir.vmessenger.core.testing"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.junit)
}
