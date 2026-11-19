pluginManagement {
    repositories {
        google(); mavenCentral(); gradlePluginPortal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") // backup copy of Maven Central
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") // backup copy of Maven Central
    }
}
rootProject.name = "Gomoku"
include(":app", ":xiangqi", ":sanguo", ":mahjong", ":chat", ":downloader", ":music", ":travel", ":earbuds")
