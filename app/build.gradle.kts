plugins {
    alias(libs.plugins.vmessenger.android.application)
    alias(libs.plugins.vmessenger.android.compose)
    alias(libs.plugins.vmessenger.android.hilt)
}

android {
    namespace = "ir.vmessenger"

    defaultConfig {
        applicationId = "ir.vmessenger.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:datastore"))
    implementation(project(":core:notifications"))
    implementation(project(":network:discovery"))
    implementation(project(":network:dht"))
    implementation(project(":network:bootstrap"))
    implementation(project(":network:transport"))
    implementation(project(":network:messaging"))
    implementation(project(":feature:identity"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:contacts"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:location"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:debug"))
    implementation(project(":feature:about"))

    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
