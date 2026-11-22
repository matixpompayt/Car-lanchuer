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
    }
}

rootProject.name = "CarLauncher"
include(":app")
// wspólny kod z CAR OS Connect (protokół, szyfrowanie, stacje przy trasie) – Connect pobiera ten katalog przy budowaniu
include(":core")
