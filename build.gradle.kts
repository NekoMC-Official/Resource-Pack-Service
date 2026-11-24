import java.io.File
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "moe.nekomc"
version = providers.gradleProperty("version").getOrElse("0.1.0")

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.21-alpha")
    compileOnly("com.google.code.gson:gson:2.11.0")

    // Kotlin 运行时随插件分发：服务端只保证 Java 标准库
    implementation(kotlin("stdlib"))

    testImplementation("io.papermc.paper:paper-api:26.3.build.21-alpha")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    // 与 kotlin.jvmTarget 同档，不一致会直接编译失败
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

val rustPlatformName: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val family = if (os.contains("win")) "windows" else if (os.contains("mac")) "macos" else "linux"
    val cpu = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
    "rps-pack-" + family + "-" + cpu + if (family == "windows") ".exe" else ""
}

tasks.named<Jar>("shadowJar") {
    dependsOn(stageRustBinary)
    from(layout.buildDirectory.dir("dist/bin")) {
        into("rps-pack/bin")
        rename { rustPlatformName }
    }
    archiveBaseName.set("resource-pack-service")
    archiveClassifier.set("")
    archiveVersion.set("")
}

// ---- Rust 合并器（可选产物，本机没有 cargo 时自动跳过） ----

val rustDir = layout.projectDirectory.dir("rust/rps-pack")
// 只做 PATH 扫描，不起进程；cargo 未安装时整段 Rust 任务静默跳过
val cargoOnPath: Boolean = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.any { File(it, "cargo.exe").isFile || File(it, "cargo").isFile } == true

// rustup 默认把 cargo 装在 ~/.cargo/bin，未加入 PATH 时也能找到
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
    from(rustDir.dir("target/release")) {
        include("rps-pack.exe", "rps-pack")
    }
    into(layout.buildDirectory.dir("dist/bin"))
}

tasks.named("build") {
    dependsOn(stageRustBinary)
}
