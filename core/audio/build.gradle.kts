plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.core.audio"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.concentus)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
