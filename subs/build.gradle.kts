plugins {
    id("com.android.application")
}

android {
    namespace = "com.zlc.subs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zlc.subs"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // On-device Chinese + Latin text recognition for importing bill screenshots (model bundled in the APK).
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
