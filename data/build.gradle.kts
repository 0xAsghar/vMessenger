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
    implementation(project(":core:crypto"))
    implementation(project(":core:datastore"))
    implementation(project(":core:proto"))
    implementation(project(":core:common"))
    implementation(project(":core:audio"))
    implementation(project(":core:location"))
    implementation(project(":core:notifications"))
    // `api`, not `implementation`: :app's Hilt component has to see the updater's
    // own types to construct UpdateRepositoryImpl.
    api(project(":core:update"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // RoomTransactionRunner wraps VMessengerDatabase.withTransaction for atomic backup restores.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.okhttp)
    // UpdateRepositoryTest serves a whole GitHub release — JSON, APK, checksums,
    // SIGNING.txt — off a local server instead of mocking OkHttp.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":core:crypto"))
}
