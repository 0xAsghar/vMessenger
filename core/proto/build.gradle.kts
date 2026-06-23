plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.protobuf)
}

android {
    namespace = "ir.vmessenger.core.proto"
}

dependencies {
    implementation(project(":core:common"))
    api(libs.protobuf.java.lite)
}
