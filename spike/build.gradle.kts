plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:protocol"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.zcode.android.spike.SpikeRunnerKt")
}
