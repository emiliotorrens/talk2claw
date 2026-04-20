plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
}

android {
    namespace = "com.emiliotorrens.talk2claw"
    compileSdk = 35

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.emiliotorrens.talk2claw"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Ed25519 crypto (device identity)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room (persistent history)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Picovoice Porcupine (wake word detection)
    implementation("ai.picovoice:porcupine-android:4.0.0")

    // ONNX Runtime (speaker verification) — disabled until model is bundled
    // Adds ~60MB to APK. Re-enable when ECAPA-TDNN model is ready.
    // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // gRPC + Protobuf (TTS over gRPC)
    implementation("io.grpc:grpc-okhttp:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("io.grpc:grpc-protobuf-lite:1.62.2")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
