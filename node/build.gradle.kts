plugins {
    alias(libs.plugins.vmessenger.jvm.library)
    alias(libs.plugins.protobuf)
    application
}

group = "ir.vmessenger.node"

application {
    mainClass.set("ir.vmessenger.node.NodeMain")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lazysodium.java)
    implementation(libs.jna)

    testImplementation(libs.junit)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

sourceSets {
    main {
        proto {
            srcDir("${rootProject.projectDir}/core/proto/src/main/proto")
        }
    }
}
