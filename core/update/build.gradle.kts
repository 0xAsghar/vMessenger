plugins {
    alias(libs.plugins.vmessenger.android.library)
    alias(libs.plugins.vmessenger.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ir.vmessenger.core.update"
}

dependencies {
    implementation(project(":core:common"))
    // FileProvider: the package installer only accepts a content Uri.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    // `api`: the release DTOs and the cached offer are @Serializable and public,
    // so every module that touches them needs the annotations on its classpath.
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
