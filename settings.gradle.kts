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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "truffnotes"

include(":tn-core")
project(":tn-core").projectDir = file("modules/tn-core")

include(":tn-test-support")
project(":tn-test-support").projectDir = file("modules/tn-test-support")

include(":tn-store")
project(":tn-store").projectDir = file("modules/tn-store")

include(":tn-platform")
project(":tn-platform").projectDir = file("modules/tn-platform")

include(":tn-runtime")
project(":tn-runtime").projectDir = file("modules/tn-runtime")

include(":tn-ui")
project(":tn-ui").projectDir = file("modules/tn-ui")

include(":app")
project(":app").projectDir = file("app")

