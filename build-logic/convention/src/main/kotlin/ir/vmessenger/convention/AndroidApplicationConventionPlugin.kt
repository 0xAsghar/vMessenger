package ir.vmessenger.convention

import com.android.build.api.dsl.ApplicationExtension
import ir.vmessenger.convention.configureAndroid
import ir.vmessenger.convention.configureKotlinAndroid
import ir.vmessenger.convention.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")

            extensions.configure<ApplicationExtension> {
                configureAndroid(this)
                defaultConfig {
                    targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
                    versionCode = 1
                    versionName = "0.1.0"
                }
            }
            configureKotlinAndroid()
        }
    }
}
