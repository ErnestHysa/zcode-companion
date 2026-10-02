import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// core/notifications — Phase 8 (§43.2-8.1/8.3/8.4; §24/§25): the §24.1
// transport-independent NotificationBroker (the SOLE notification decision point —
// UI components never create or cancel notifications directly), §24.2 channels,
// §24.5/§25.2 the Live-connection FGS candidate (specialUse behind user opt-in),
// §25.3 the WorkManager auto-reconnect probe, and the deep-link PendingIntents
// (§24.7). Depends on core:common + domain only — never on :data (the record store
// is a port here, implemented in :data), never on transport/UI (INV-2/6).
android {
    namespace = "com.zcode.android.core.notifications"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
}
