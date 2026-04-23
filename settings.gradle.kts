pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        flatDir { dirs("swarmnode/libs") }
    }
}

rootProject.name = "freedom-browser-android"
include(":app")
include(":swarmnode")
