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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "arkikeskus-launcher"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:data")
include(":core:launcher")
include(":core:ui")
include(":feature:home")
include(":feature:appdrawer")
include(":feature:settings")
include(":feature:backup")
