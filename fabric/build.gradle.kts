plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.19.4"
}

group = "moe.nekomc"
version = providers.gradleProperty("version").getOrElse("0.1.0")

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.167.0+26.3")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
