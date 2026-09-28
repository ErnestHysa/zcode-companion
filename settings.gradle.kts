pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "zcode-android"

include(":core:common")
include(":core:protocol")
include(":core:transport")
include(":spike")
include(":tools:mockzcode")
