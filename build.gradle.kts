import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.loom)
    id("io.github.izhangzhihao.unmeta") version "1.0.3"
}

unmeta {
    enable.set(true)
}

version = property("mod_version").toString()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net")
    maven("https://jitpack.io")
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.terraformersmc.com/releases")
    maven("https://repo.essential.gg/repository/maven-public")
    maven("https://repo.hypixel.net/repository/Hypixel/")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    // To change the versions see the gradle/libs.versions.toml
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.fabric.kotlin)

    implementation(libs.bundles.included) { include(this) }
    implementation(libs.universalcraft) {
        include(this)
        exclude("gg.essential", "universalcraft-1.18.1-fabric")
    }
    implementation(libs.elementa) { include(this) }
    implementation(libs.vigilance) {
        include(this)
        exclude(group = "gg.essential", module = "elementa")
    }

    compileOnly(libs.sponge.mixin)
    ksp(project(":typing-generator"))
    // Discord IPC
    implementation("meteordevelopment:discord-ipc:1.1")
    include("meteordevelopment:discord-ipc:1.1")

    // NanoVG (with natives)
    implementation(libs.lwjgl.nanovg) { include(this) }
    listOf("windows", "linux", "macos", "macos-arm64").forEach {
        implementation(variantOf(libs.lwjgl.nanovg) { classifier("natives-$it") }) {
            include(this)
        }
    }

    // Mixin Extras
    implementation(libs.mixinextras) { include(this) }

    // Proxy support
    implementation("io.netty:netty-handler-proxy:4.2.7.Final")
    include("io.netty:netty-handler-proxy:4.2.7.Final")
    implementation("io.netty:netty-codec-socks:4.2.7.Final")
    include("io.netty:netty-codec-socks:4.2.7.Final")

    compileOnly(libs.hypixel.mod.api)
    implementation(libs.hypixel.modrinth.api) { include(this) }
}

loom {
    accessWidenerPath.set(file("src/main/resources/ctjs.accesswidener"))
}

base {
    archivesName.set(property("archives_base_name") as String)
}

java {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }

    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks {
    processResources {
        val mcVersion = libs.versions.minecraft.get()
        val flkVersion = libs.versions.fabric.kotlin.get()
        val fapiVersion = libs.versions.fabric.api.get()
        val loaderVersion = libs.versions.loader.get()

        from("typing-generator/src/main/resources") {
            include("provided-types.properties")
        }

        inputs.property("version", project.version)
        inputs.property("minecraft_version", mcVersion)
        inputs.property("fabric_kotlin_version", flkVersion)
        inputs.property("fabric_api_version", fapiVersion)
        inputs.property("loader_version", loaderVersion)

        filesMatching("fabric.mod.json") {
            expand(
                "version" to project.version,
                "minecraft_version" to mcVersion,
                "fabric_kotlin_version" to flkVersion,
                "fabric_api_version" to fapiVersion,
                "loader_version" to loaderVersion
            )
        }
    }

    withType<JavaCompile>().configureEach {
        options.release.set(25)
    }

    kotlin {
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
            freeCompilerArgs = listOf("-Xcontext-receivers")
        }
    }

    jar {
        archiveFileName.set(if (providers.gradleProperty("releaseBuild").isPresent) "V5-Loader-26.1.2.jar" else "V5-Loader-DEV.jar")
        exclude("typings.d.ts")
    }

    register<Copy>("generateTypings") {
        description = "Regenerates typing-generator/src/main/resources/typings.d.ts"
        group = "build"
        dependsOn("kspKotlin")
        from(layout.buildDirectory.dir("generated/ksp/main/resources")) {
            include("typings.d.ts")
        }
        into(layout.projectDirectory.dir("typing-generator/src/main/resources"))
    }
}
