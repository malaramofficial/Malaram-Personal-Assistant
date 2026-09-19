plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.malaram.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.malaram.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Official Sherpa-ONNX Android AAR. Downloaded and SHA-256 verified by GitHub Actions.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // Used only on first run to unpack the official Piper Hindi voice model.
    implementation("org.apache.commons:commons-compress:1.27.1")
}
