pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "自动任务"

include(
    ":app",
    ":hidden-apis",
    ":ui-automator",
    ":tasker-engine",
    ":shared-library",
    ":coroutine-ui-automator",
    ":ssl",
)
