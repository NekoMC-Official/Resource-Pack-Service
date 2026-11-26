import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm")
    id("net.fabricmc.fabric-loom") version "1.19.4"
}

// 产物名与 Paper 侧同风格，Release 里两个 jar 一眼能分开。
base {
    archivesName.set("resource-pack-service-fabric")
}

// Fabric 适配层：只有这一层允许 import net.minecraft 与 net.fabricmc。
dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.167.0+26.3")

    // 内核（契约、合并、下发编排）与 Kotlin 运行时都塞进 mod 的嵌套 jar，
    // 服务器不需要预装 Kotlin，也不会和别人的版本打架。
    implementation(project(":core"))
    include(project(":core"))
    include("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")

    // 配置解析：Fabric 上没有 Bukkit 的 YamlConfiguration，自带一份并嵌套进 jar。
    implementation("org.yaml:snakeyaml:2.4")
    include("org.yaml:snakeyaml:2.4")

    // gson 由 Minecraft 自带，编译期借用，不打进包
    compileOnly("com.google.code.gson:gson:2.15.0")
}

// 回执钩子靠 mixin：refmap 名字固定，避免和别的 mod 撞。
loom {
    mixin {
        defaultRefmapName.set("resource-pack-service.refmap.json")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

kotlin {
    jvmToolchain(26)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

// ---- Rust 合并器（与 paper 同一份二进制，构建期打进 mod jar） ----
val rustDir = rootProject.layout.projectDirectory.dir("rust/rps-pack")

val rustPlatformName: String = {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val family = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    val cpu = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
    "rps-pack-" + family + "-" + cpu + (if (family == "windows") ".exe" else "")
}()

val cargoOnPath: Boolean = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.any { File(it, "cargo.exe").isFile || File(it, "cargo").isFile } == true

val cargoBin = File(System.getProperty("user.home"), ".cargo" + File.separator + "bin")
val cargoExe: String? = when {
    cargoOnPath -> "cargo"
    File(cargoBin, "cargo.exe").isFile -> File(cargoBin, "cargo.exe").absolutePath
    File(cargoBin, "cargo").isFile -> File(cargoBin, "cargo").absolutePath
    else -> null
}

val cargoBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "编译 Rust 合并器 rps-pack"
    workingDir = rustDir.asFile
    cargoExe?.let { commandLine(it, "build", "--release") }
    onlyIf { cargoExe != null && rustDir.file("Cargo.toml").asFile.isFile }
}

val stageRustBinary by tasks.registering(Copy::class) {
    group = "rust"
    description = "把合并器复制到 build/dist/bin"
    dependsOn(cargoBuild)
    from(rustDir.dir("target/release"))
    include("rps-pack.exe", "rps-pack")
    into(layout.buildDirectory.dir("dist/bin"))
}

tasks.processResources {
    dependsOn(stageRustBinary)
    from(layout.buildDirectory.dir("dist/bin")) {
        into("rps-pack/bin")
        rename { rustPlatformName }
    }
    // 配置模板与 Paper 共用一份，避免两边默认值漂移。
    from(rootProject.layout.projectDirectory.file("paper/src/main/resources/config.yml"))
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.named("build") {
    dependsOn(stageRustBinary)
}
