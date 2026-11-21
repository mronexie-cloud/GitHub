plugins {
    id("com.android.application")
}

android {
    namespace = "com.zlc.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zlc.video"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // WebSocket client for the free Edge neural voices.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}

// The live voice test only runs when CI sets EDGE_TTS_LIVE=1; it hands the variable through here.
tasks.withType<Test>().configureEach {
    environment("EDGE_TTS_LIVE", System.getenv("EDGE_TTS_LIVE") ?: "")
    environment("EDGE_TTS_OUT", System.getenv("EDGE_TTS_OUT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
