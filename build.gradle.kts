plugins {
    // 只做声明，具体应用到各自模块：:core / :paper 用 Kotlin，:fabric 由 loom 带
    kotlin("jvm") version "2.4.21" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "moe.nekomc"
    version = providers.gradleProperty("version").getOrElse("0.1.0")

    repositories {
        maven("https://repo.papermc.io/repository/maven-public/")
        mavenCentral()
    }
}
