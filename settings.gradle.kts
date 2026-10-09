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

rootProject.name = "zcode-companion"

include(":core:common")
include(":core:ui")
include(":core:protocol")
include(":core:transport")
include(":core:security")
include(":core:notifications")
include(":domain")
include(":data")
include(":features:pairing")
include(":features:tasks")
include(":features:conversation")
include(":features:terminal")
include(":features:git")
include(":features:files")
include(":app")
include(":baselineprofile")
include(":spike")
include(":tools:mockzcode")
