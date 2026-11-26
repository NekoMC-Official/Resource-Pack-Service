pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // lorenz 等 loom 的传递依赖在中央仓；个别网络下 plugins.gradle.org 的 TLS 会被代理掐断，
        // 因此中央仓排在门户之前（两者都有的坐标优先走中央仓）
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "resource-pack-service"

// 三语言分层：:core 平台无关（Java 契约 + Kotlin 编排），:paper 是 Bukkit/Folia 适配层，
// :fabric 是 Fabric 服务端适配层（P0 阶段先只挂 loom，P2 起接 :core）。
include(":core")
include(":paper")
include(":fabric")
