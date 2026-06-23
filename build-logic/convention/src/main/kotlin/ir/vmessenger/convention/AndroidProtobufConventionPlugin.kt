package ir.vmessenger.convention

import com.google.protobuf.gradle.ProtobufExtension
import com.google.protobuf.gradle.id
import ir.vmessenger.convention.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidProtobufConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.protobuf")

            extensions.configure<ProtobufExtension> {
                protoc {
                    artifact = "com.google.protobuf:protoc:${libs.findVersion("protobuf").get()}"
                }
                generateProtoTasks {
                    all().forEach { task ->
                        task.builtins {
                            id("java") {
                                option("lite")
                            }
                            id("kotlin") {
                                option("lite")
                            }
                        }
                    }
                }
            }

            dependencies {
                add("implementation", libs.findLibrary("protobuf-java-lite").get())
                add("implementation", libs.findLibrary("protobuf-kotlin-lite").get())
            }
        }
    }
}
