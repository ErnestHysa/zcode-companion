import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// §40.1 app — MainActivity, AppContainer (manual DI), theme wiring. Phase 3 ships the
// pairing flow as the sole destination; task/conversation surfaces arrive in Phase 6.
android {
    namespace = "com.zcode.android.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zcode.android.app"
        // Benchmark-only debug seeding hook; dead (false) in every other type.
        buildConfigField("boolean", "BENCHMARK_SEEDING", "false")
        minSdk = 26
        targetSdk = 36
        versionCode = 40
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Emulator/device E2E against the in-repo MockZCode (§35.4) — cleartext is
            // allowed ONLY for loopback endpoints in debug builds (INV-15: mock-only).
            manifestPlaceholders["usesCleartextBackup"] = true
            // §31 note: AGP 9 removed pseudoLocalesEnabled — the pseudolocale leg is
            // the qualifier-pinned Robolectric suite (ar-rXB/en-rXA + zh-rCN).
        }
        // §43.2-11.1: the macrobenchmark target — non-debuggable (profiling-faithful),
        // debug-signed, loopback cleartext for the MockZCode seeding (INV-15).
        create("benchmark") {
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "BENCHMARK_SEEDING", "true")
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["usesCleartextBackup"] = true
            proguardFiles("proguard-rules.pro")
        }
        release {
            isMinifyEnabled = false
            // AAP-5 prerequisite (Phase-12.1 minimum, §37 "single stable key from day
            // one"): the release keystore lives OFF-REPO at
            // ~/.zcode-android-release-keys/ (keystore.properties, 0600). When it is
            // absent (any other machine / fresh clone) the build stays UNSIGNED —
            // exactly the pre-Phase-6 behavior. The key is never committed, never in CI.
            val keystorePropsFile = file(System.getProperty("user.home")
                ?.let { "$it/.zcode-android-release-keys/keystore.properties" } ?: "")
            if (keystorePropsFile.isFile) {
                val keystoreProps = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                signingConfigs {
                    create("release") {
                        storeFile = file(keystoreProps.getProperty("storeFile"))
                        storePassword = keystoreProps.getProperty("storePassword")
                        keyAlias = keystoreProps.getProperty("keyAlias")
                        keyPassword = keystoreProps.getProperty("keyPassword")
                    }
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // E6-lint (gaps-and-polish 2A): Compose lint discipline. slack compose-lints
    // (lintChecks below) + Compose's own bundled checks gate the build; the bug
    // classes Part B found by hand (bare clickable{} without roles, sub-48dp targets,
    // literal ARGB, early-return-before-remember) can no longer regress silently.
    // checkDependencies runs the whole set over every feature module from this one
    // task (`gradle :app:lintDebug`, also the CI gate). Pre-existing findings are
    // triaged into lint-baseline.xml — new ones fail the build; burn the baseline down
    // as the a11y/chrome phases land (never add to it without a finding ID).
    lint {
        checkDependencies = true
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        htmlReport = true
        xmlReport = true
        // Not a finding class we act on: the app is a companion and pins its own sdk.
        disable += listOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // E6-lint (gaps-and-polish 2A): Compose lint discipline runs over this module.
    lintChecks(libs.compose.lint.checks)
    implementation(project(":core:common"))
    implementation(project(":core:notifications"))
    implementation(project(":core:security"))
    implementation(project(":core:transport"))
    implementation(project(":core:ui"))
    implementation(project(":data"))
    implementation(project(":domain"))
    implementation(project(":features:pairing"))
    implementation(project(":features:tasks"))
    implementation(project(":features:conversation"))
    implementation(project(":features:terminal"))
    implementation(project(":features:git"))
    implementation(project(":features:files"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.navigation.compose)
    // §43.2-11.4: the pending-approvals Glance widget (§44 "Glance (Phase 3, widget)"; ADR-018).
    implementation(libs.glance.appwidget)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    // MockZCode is TEST-ONLY: it must never enter a shipped artifact (INV-15).
    testImplementation(project(":tools:mockzcode"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(project(":tools:mockzcode"))
    androidTestImplementation(project(":core:security"))
    androidTestImplementation(project(":core:notifications"))
    androidTestImplementation(libs.work.runtime.ktx)
    // AAP-3: the update flow instrumented test seeds a loopback releases+APK server.
    androidTestImplementation(libs.mockwebserver3)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestDebugImplementation(libs.compose.ui.test.manifest)
}
