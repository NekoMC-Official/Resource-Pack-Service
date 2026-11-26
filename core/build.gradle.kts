import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm")
}

// 内核：平台无关。这里不允许出现 org.bukkit / io.papermc / net.minecraft 的 import，
// 与具体服务端打交道的能力一律通过 moe.nekomc.resourcepack.platform 下的端口注入。
dependencies {
    // 只在 JSON 校验里用到，服务端已自带，编译期依赖即可
    compileOnly("com.google.code.gson:gson:2.15.0")

    // Kotlin 运行时随插件分发：服务端只保证 Java 标准库
    implementation(kotlin("stdlib"))

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

tasks.test {
    useJUnitPlatform()
}
