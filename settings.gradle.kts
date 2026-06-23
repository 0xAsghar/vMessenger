pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vMessenger"

include(":app")
include(":domain")
include(":data")

include(":core:common")
include(":core:crypto")
include(":core:proto")
include(":core:database")
include(":core:storage")
include(":core:datastore")
include(":core:location")
include(":core:notifications")
include(":core:designsystem")
include(":core:testing")

include(":network:discovery")
include(":network:dht")
include(":network:bootstrap")
include(":network:transport")
include(":network:messaging")

include(":feature:identity")
include(":feature:pairing")
include(":feature:contacts")
include(":feature:chat")
include(":feature:location")
include(":feature:settings")
include(":feature:debug")
include(":feature:about")

include(":node")
