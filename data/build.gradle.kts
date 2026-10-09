import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

// §40.1 data — "Repositories implementing domain interfaces: projection merge, caches
// (DataStore), outbox, journal persistence. Depends on domain + core." Room/DataStore
// split per ADR-008: Room owns tasks/pages/journal/outbox/interactions/drafts; DataStore
// (proto) owns preferences/endpoint descriptors/credential envelopes.
android {
    namespace = "com.zcode.android.data"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("-Xmx1g")
                it.systemProperty("ZCODE_MATRIX_DEBUG", System.getProperty("ZCODE_MATRIX_DEBUG") ?: "")
            }
        }
    }
    sourceSets {
        // Migration-test harness (§32.3): MigrationTestHelper reads the committed
        // schema exports as test assets.
        getByName("test").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // §32.3: Room schema exports committed per shipped version; migration tests from v1.
    arg("room.schemaLocation", "$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    // AGP 9 registers the library plugin under an internal id, so the plugin's
    // default java-builtin wiring doesn't fire — register it explicitly for every
    // generate task (protoc otherwise fails with "Missing output directives").
    generateProtoTasks {
        all().configureEach {
            builtins {
                // javalite runtime: generated code must be the LITE flavor
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(project(":domain"))
    api(project(":core:common"))
    implementation(project(":core:protocol")) // protocol→domain mapping + UuidV7 (§40.2 DATA→PROT)
    api(libs.kotlinx.coroutines.core)
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    api(libs.datastore)
    api(libs.datastore.preferences)
    api(libs.protobuf.javalite)
    implementation(project(":core:security")) // DataStore credential-store binding (§12.4)
    implementation(project(":core:notifications")) // §9.2 REPO→NOTIFY edge: the broker record store port (Phase 8)
    implementation(project(":core:transport")) // §40.2 DATA→TRANS allowed edge: the Phase 5 recovery subsystem binds the session API (§17.1)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(project(":tools:mockzcode")) // §35.4 test tooling only — never shipped (INV-15)
}
