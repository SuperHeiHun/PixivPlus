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
        // Legacy Xposed API (de.robv.android.xposed:api:82) is not published on Maven Central.
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "PixivPlus"
include(":app")
