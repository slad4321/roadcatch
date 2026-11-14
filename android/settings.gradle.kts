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
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            // TextToSpeech/SpeechToText pull in ai.pleos.playground.security:*, so match subgroups too.
            content { includeGroupByRegex("ai\\.pleos\\.playground.*") }
        }
    }
}

rootProject.name = "RoadCatch"
include(":app")
