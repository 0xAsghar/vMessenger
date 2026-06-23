import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "ir.vmessenger.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.room.gradle.plugin)
    compileOnly(libs.protobuf.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "vmessenger.android.application"
            implementationClass = "ir.vmessenger.convention.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "vmessenger.android.library"
            implementationClass = "ir.vmessenger.convention.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "vmessenger.android.compose"
            implementationClass = "ir.vmessenger.convention.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "vmessenger.android.hilt"
            implementationClass = "ir.vmessenger.convention.AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "vmessenger.android.room"
            implementationClass = "ir.vmessenger.convention.AndroidRoomConventionPlugin"
        }
        register("androidProtobuf") {
            id = "vmessenger.android.protobuf"
            implementationClass = "ir.vmessenger.convention.AndroidProtobufConventionPlugin"
        }
        register("jvmLibrary") {
            id = "vmessenger.jvm.library"
            implementationClass = "ir.vmessenger.convention.JvmLibraryConventionPlugin"
        }
    }
}
