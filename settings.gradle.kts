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

include(":tn-render")
project(":tn-render").projectDir = file("modules/tn-render")

include(":tn-ink")
project(":tn-ink").projectDir = file("modules/tn-ink")

include(":tn-objects")
project(":tn-objects").projectDir = file("modules/tn-objects")

include(":tn-platform")
project(":tn-platform").projectDir = file("modules/tn-platform")

include(":tn-runtime")
project(":tn-runtime").projectDir = file("modules/tn-runtime")

include(":tn-ui")
project(":tn-ui").projectDir = file("modules/tn-ui")

include(":app")
project(":app").projectDir = file("app")


include(":tn-pages")
project(":tn-pages").projectDir = file("modules/tn-pages")

include(":tn-text")
project(":tn-text").projectDir = file("modules/tn-text")

include(":tn-content")
project(":tn-content").projectDir = file("modules/tn-content")

include(":tn-library")
project(":tn-library").projectDir = file("modules/tn-library")

include(":tn-workspace")
project(":tn-workspace").projectDir = file("modules/tn-workspace")

include(":tn-pdf")
project(":tn-pdf").projectDir = file("modules/tn-pdf")

include(":tn-exchange")
project(":tn-exchange").projectDir = file("modules/tn-exchange")

include(":tn-media")
project(":tn-media").projectDir = file("modules/tn-media")
