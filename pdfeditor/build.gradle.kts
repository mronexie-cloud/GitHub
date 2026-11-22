plugins {
    id("com.android.application")
}

android {
    namespace = "com.zlc.pdfeditor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zlc.pdfeditor"
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
}

// 编译时把 PDF 组件下载进 APK（assets/lib），APP 离线也能用
val webLibs = mapOf(
    "pdf.min.js" to "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js",
    "pdf.worker.min.js" to "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js",
    "pdf-lib.min.js" to "https://cdnjs.cloudflare.com/ajax/libs/pdf-lib/1.17.1/pdf-lib.min.js",
    "fontkit.umd.min.js" to "https://cdn.jsdelivr.net/npm/@pdf-lib/fontkit@1.1.1/dist/fontkit.umd.min.js",
)
val libDir = file("src/main/assets/lib")
val fetchWebLibs by tasks.registering {
    doLast {
        libDir.mkdirs()
        webLibs.forEach { (name, url) ->
            val f = File(libDir, name)
            if (!f.exists() || f.length() == 0L) {
                java.net.URI(url).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                println("downloaded $name (${f.length()} bytes)")
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchWebLibs) }
