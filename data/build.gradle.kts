plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.data"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":network:discovery"))
    implementation(project(":network:dht"))
    implementation(project(":network:bootstrap"))
    implementation(project(":network:transport"))
    implementation(project(":network:messaging"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:datastore"))
    implementation(project(":core:proto"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
