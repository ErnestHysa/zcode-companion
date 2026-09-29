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
include(":core:ui")
include(":core:protocol")
include(":core:transport")
include(":core:security")
include(":domain")
include(":data")
include(":features:pairing")
include(":features:tasks")
include(":features:conversation")
include(":app")
include(":spike")
include(":tools:mockzcode")
