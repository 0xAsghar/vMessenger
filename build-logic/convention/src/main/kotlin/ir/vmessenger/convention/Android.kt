package ir.vmessenger.convention

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal fun Project.configureAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

        defaultConfig {
            minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

internal fun Project.configureJavaToolchain() {
    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    tasks.withType<JavaCompile>().configureEach {
        javaCompiler.set(
            toolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            },
        )
    }
}

internal fun Project.configureKotlinAndroid() {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}

/**
 * Pure-JVM modules compile with the 21 toolchain but emit Java 17 bytecode against the Java 17 API.
 * They include `:node`, which runs on the server's own JRE — and Debian 12's newest is 17. Android
 * modules are unaffected: they consume these jars as they would any older library.
 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=$JVM_LIBRARY_RELEASE")
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(JVM_LIBRARY_RELEASE)
    }
}

internal const val JVM_LIBRARY_RELEASE = 17
