plugins {
    id("com.android.application")
}

android {
    namespace = "com.zlc.travel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zlc.travel"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.8.1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    androidResources {
        // the photos are already compressed JPEGs
        noCompress += listOf("jpg")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
