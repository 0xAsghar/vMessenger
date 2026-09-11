import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.room) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.from(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
        // Pre-existing structural findings live in the baseline; new code must stay clean.
        baseline = file("$projectDir/detekt-baseline.xml")
    }

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Every unit test in the build: Android modules expose `testDebugUnitTest`,
// while JVM modules (core:common, domain, node) only expose `test` — a plain
// `./gradlew testDebugUnitTest` silently skips the latter.
tasks.register("unitTests") {
    group = "verification"
    description = "Runs testDebugUnitTest in Android modules and test in JVM modules"
    dependsOn(
        subprojects.map { sp ->
            sp.tasks.matching { t ->
                t.name == "testDebugUnitTest" ||
                    (t.name == "test" && sp.plugins.hasPlugin("org.jetbrains.kotlin.jvm"))
            }
        },
    )
}
