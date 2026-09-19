plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Offline neural TTS runtime for Android.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    // Used only on first run to unpack the official Piper Hindi voice model.
    implementation("org.apache.commons:commons-compress:1.27.1")
}
