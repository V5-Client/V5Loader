pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net")
        maven("https://maven.kikugie.dev/releases")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
}

stonecutter {
    create(rootProject) {
        versions("26.1.2", "26.2")
        vcsVersion = "26.2"
    }
}

rootProject.name = "ctjs"
include(":typing-generator")
