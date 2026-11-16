// Maven Central is rate-limited through the sandbox proxy, so Google's mirror of it comes first.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2"
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        gradlePluginPortal()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        maven(centralMirror)
        google()
    }
}
rootProject.name = "street25d"
include(":desktop")
include(":app")
