pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "zcode-android"

include(":core:common")
include(":core:protocol")
include(":core:transport")
include(":core:security")
include(":features:pairing")
include(":app")
include(":spike")
include(":tools:mockzcode")
