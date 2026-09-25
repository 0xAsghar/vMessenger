import ir.vmessenger.convention.BundleNodeInstallerTask
import java.util.Properties

plugins {
    alias(libs.plugins.vmessenger.android.application)
    alias(libs.plugins.vmessenger.android.compose)
    alias(libs.plugins.vmessenger.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ir.vmessenger"

    defaultConfig {
        applicationId = "ir.vmessenger.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        // BuildConfig.DEBUG / VERSION_NAME drive debug-screen gating and the node-address policy.
        buildConfig = true
    }

    // The node tarball is already gzip; compressing it again only costs time on every read.
    androidResources {
        noCompress += "tgz"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing policy: the release keystore when configured; on CI without
            // one build UNSIGNED (the release workflow refuses to publish anyway)
            // so a debug-signed "release" can never be produced by automation.
            // Local builds fall back to the debug key for on-device testing.
            val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            val onCi = System.getenv("CI") == "true"
            signingConfig = when {
                releaseKeystorePath != null -> signingConfigs.getByName("release")
                onCi -> null
                else -> signingConfigs.getByName("debug").also {
                    logger.warn("assembleRelease: DEBUG-signed (ANDROID_KEYSTORE_PATH unset) — local use only")
                }
            }
        }
    }
}

// The node installer the app uploads to a server over SSH ("New node"): script, templates, node
// tarball, manifest and checksums, as assets under node-installer/.
val nodeDistTar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val bundleNodeInstaller = tasks.register<BundleNodeInstallerTask>("bundleNodeInstaller") {
    installer.set(rootProject.layout.projectDirectory.file("scripts/setup-node.sh"))
    deployDir.set(rootProject.layout.projectDirectory.dir("deploy"))
    nodeTarball.from(nodeDistTar)
    nodeVersion.set(
        Properties().also { props -> rootProject.file("gradle/version.properties").inputStream().use(props::load) }
            .getProperty("versionName"),
    )
    outputDir.set(layout.buildDirectory.dir("generated/node-installer"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleNodeInstaller, BundleNodeInstallerTask::outputDir)
    }
}

dependencies {
    nodeDistTar(project(":node", "nodeDistTar"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":core:database"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(project(":core:location"))
    implementation(project(":core:notifications"))
    implementation(project(":network:discovery"))
    implementation(project(":network:dht"))
    implementation(project(":network:bootstrap"))
    implementation(project(":network:transport"))
    implementation(project(":network:messaging"))
    implementation(project(":feature:identity"))
    implementation(project(":feature:lock"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:contacts"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:map"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:debug"))
    implementation(project(":feature:about"))

    implementation(libs.maplibre.android)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
