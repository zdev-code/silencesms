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

include(":app")
include(":core-models")

include(":org.whispersystems.jobmanager")
project(":org.whispersystems.jobmanager").projectDir = file("third-party/jobmanager")

include(":java", ":tests")
project(":tests").projectDir = file("third-party/libsignal/tests")
project(":java").projectDir = file("third-party/libsignal/java")
include(":org.whispersystems.libsignal")
project(":org.whispersystems.libsignal").projectDir = file("third-party/libsignal/android")
