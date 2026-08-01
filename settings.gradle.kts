pluginManagement {
    // An included build rather than buildSrc: buildSrc invalidates the whole
    // build's configuration cache whenever anything in it changes, and the
    // convention plugins are exactly the code that changes while a module is
    // being set up.
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

dependencyResolutionManagement {
    // Modules declare dependencies, never repositories. A module that adds its
    // own repository is a supply-chain surface nobody reviews.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "plMail"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:jmap")

include(":core:database")

include(":core:datastore")

include(":core:data")

include(":core:designsystem")

include(":core:ui")

include(":core:notifications")

include(":feature:onboarding")

include(":feature:mail")

include(":feature:compose")

include(":feature:search")

include(":feature:settings")
