pluginManagement {
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
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DesChat"

include(":app")
include(":domain")
include(":ads")

include(":core-common")
project(":core-common").projectDir = file("core/common")

include(":core-model")
project(":core-model").projectDir = file("core/model")

include(":mesh-protocol")
project(":mesh-protocol").projectDir = file("mesh/protocol")

include(":mesh-transport")
project(":mesh-transport").projectDir = file("mesh/transport")

include(":mesh-transport-ble")
project(":mesh-transport-ble").projectDir = file("mesh/transport-ble")

include(":feature-onboarding")
project(":feature-onboarding").projectDir = file("feature/onboarding")

include(":feature-nearby")
project(":feature-nearby").projectDir = file("feature/nearby")

include(":feature-chat-detail")
project(":feature-chat-detail").projectDir = file("feature/chat-detail")

include(":feature-settings")
project(":feature-settings").projectDir = file("feature/settings")
include(":data")
