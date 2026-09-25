plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.data"
}

dependencies {
    // "New node": SSH to the server (sshj + BouncyCastle); sshj's slf4j logging goes nowhere.
    api(project(":core:ssh"))
    api(project(":core:nodesetup"))
    implementation(libs.bouncycastle.prov)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.okhttp)
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // RoomTransactionRunner wraps VMessengerDatabase.withTransaction for atomic backup restores.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lazysodium.java)
    testImplementation(project(":core:crypto"))
}
