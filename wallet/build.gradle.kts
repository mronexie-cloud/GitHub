plugins {
    id("com.android.application")
}

android {
    namespace = "com.zlc.wallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zlc.wallet"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
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
