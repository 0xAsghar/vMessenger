plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger.core.crypto"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:proto"))
    implementation(libs.protobuf.java.lite)
    // @aar is required so libjnidispatch.so and 16 KB-aligned libsodium.so are packaged.
    implementation("com.goterl:lazysodium-android:${libs.versions.lazysodium.get()}@aar") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
