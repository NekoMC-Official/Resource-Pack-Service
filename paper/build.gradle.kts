import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm")
    id("com.gradleup.shadow")
}

// Paper / Folia 适配层：只有这一层允许 import org.bukkit 与 io.papermc。
dependencies {
    implementation(project(":core"))

    compileOnly("io.papermc.paper:paper-api:26.3.build.21-alpha")
    compileOnly("com.google.code.gson:gson:2.15.0")

    testImplementation("io.papermc.paper:paper-api:26.3.build.21-alpha")
    testImplementation("com.google.code.gson:gson:2.15.0")
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
    // 只交付 shadow jar；plain jar 不上传也不保留，避免 build/libs 出现第二个包
    enabled = false
}

// ---- Rust 合并器（构建期打进 jar，运行期解压到数据目录） ----
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

val stageRustBinary by tasks.registering(Sync::class) {
    group = "rust"
    description = "把合并器复制到 build/dist/bin"
    dependsOn(cargoBuild)
    from(rustDir.dir("target/release"))
    include("rps-pack.exe", "rps-pack")
    into(layout.buildDirectory.dir("dist/bin"))
}


// 记下这份 jar 是给哪个平台构建的：运行期发现合并器平台不符时能直说差在哪
val writePlatformMarker by tasks.registering {
    group = "rust"
    description = "写 dist/marker/platform.txt，标记构建平台"
    dependsOn(stageRustBinary)
    val markerDir = layout.buildDirectory.dir("dist/marker")
    // 平台名参与判定，否则改架构后任务仍是 UP-TO-DATE
    inputs.property("rustPlatformName", rustPlatformName)
    // staging 的产物也要参与判定：Sync 清空 dist/bin 后旧标记必须失效
    inputs.dir(layout.buildDirectory.dir("dist/bin"))
    outputs.dir(markerDir)
    doLast {
        val binDir = layout.buildDirectory.dir("dist/bin").get().asFile
        val dir = markerDir.get().asFile
        val marker = File(dir, "platform.txt")
        val staged = binDir.listFiles()?.any { it.name.startsWith("rps-pack") } == true
        if (!staged) {
            // 没打进二进制就别留下上一个平台的标记
            marker.delete()
            return@doLast
        }
        dir.mkdirs()
        marker.writeText(rustPlatformName)
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    dependsOn(writePlatformMarker)
    from(layout.buildDirectory.dir("dist/bin")) {
        into("rps-pack/bin")
        rename { rustPlatformName }
    }
    from(layout.buildDirectory.dir("dist/marker")) {
        into("rps-pack/bin")
    }
    archiveBaseName.set("resource-pack-service")
    archiveClassifier.set("")
    // 交付包带版本号，与 Release 标签一一对应；服务端部署时文件名随意
    archiveVersion.set(project.version.toString())
}

tasks.named("build") {
    dependsOn(stageRustBinary)
}
