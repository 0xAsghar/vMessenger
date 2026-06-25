plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.network.messaging"
}

dependencies {
    implementation(project(":network:transport"))
    implementation(project(":network:discovery"))
    implementation(project(":core:crypto"))
    implementation(project(":core:proto"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.lazysodium.java)
    testImplementation(project(":core:crypto"))
}
