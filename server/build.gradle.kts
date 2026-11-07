plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    kotlin("multiplatform") version "2.0.20" apply false
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("mahjong.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(project(":core"))

    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-websockets-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.12")
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.12")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("ch.qos.logback:logback-classic:1.5.8")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-cio-jvm:2.3.12")
    testImplementation("io.ktor:ktor-client-websockets-jvm:2.3.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

/** 端到端：4 个 WebSocket 客户端打完整一局。 */
tasks.register<JavaExec>("simulateFour") {
    group = "verification"
    description = "Run 4 WebSocket clients through a complete game"
    mainClass.set("mahjong.server.tools.SimulateFourKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("file.encoding", "UTF-8")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("sim.rule", project.findProperty("rule")?.toString() ?: "MCR")
}

/** 端到端：局域网发现 + 房主踢人边界。 */
tasks.register<JavaExec>("simulateLobby") {
    group = "verification"
    description = "Verify LAN discovery and host kick flows"
    mainClass.set("mahjong.server.tools.SimulateLobbyKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("file.encoding", "UTF-8")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("sim.discoveryPort", project.findProperty("discoveryPort")?.toString() ?: "45299")
}

/** 端到端：断线 → 托管 → 重连 → 打完。 */
tasks.register<JavaExec>("simulateResume") {
    group = "verification"
    description = "Verify disconnect auto-play and reconnect within grace"
    mainClass.set("mahjong.server.tools.SimulateResumeKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("file.encoding", "UTF-8")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("sim.rule", project.findProperty("rule")?.toString() ?: "MCR")
}
