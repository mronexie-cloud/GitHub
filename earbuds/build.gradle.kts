plugins {
    id("com.android.application")
}

android {
    namespace = "com.zlc.earbuds"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zlc.earbuds"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "2.2"
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
