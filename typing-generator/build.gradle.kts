plugins {
    alias(libs.plugins.kotlin)
}

kotlin {
    kotlin {
        jvmToolchain(21)
    }
}

buildscript {
    dependencies {
        classpath(libs.gradle.plugin)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ksp)
}
