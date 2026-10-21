plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tablettouch.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tablettouch.client"
        minSdk = 26          // Android 8.0+ (проект рассчитан на Android 12)
        targetSdk = 34       // Android 14 — корректно работает на Android 12
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Собирается build_apk.bat / CI; локальный ключ по умолчанию
            storeFile = file("../keystore.jks")
            storePassword = System.getenv("TT_KEYSTORE_PASS") ?: "tablettouch"
            keyAlias = "tablettouch"
            keyPassword = System.getenv("TT_KEY_PASS") ?: "tablettouch"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")   // ws-клиент без OkHttp
}
