pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "mahjong-server"

// 核心规则引擎由 core 模块提供，这里通过 projectDir 引用，不修改根工程。
include(":core")
project(":core").projectDir = file("../core")
