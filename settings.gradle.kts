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

rootProject.name = "WenuLink"
include(":app")
include(":mavlink")
include(":state-model")
include(":evaluation:pbt")
// include(":evaluation:claude:run-1")
// include(":evaluation:claude:run-2")
// include(":evaluation:claude:run-3")
// include(":evaluation:google:run-1")
// include(":evaluation:google:run-2")
// include(":evaluation:google:run-3")
// include(":evaluation:openai:run-1")
// include(":evaluation:openai:run-2")
// include(":evaluation:openai:run-3")
