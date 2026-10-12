pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Lets Gradle download a JDK 25 toolchain if none is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ffm-proto"
