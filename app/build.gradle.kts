import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
            }
        }
        // 非 macOS 环境可能不会创建 iosMain（Kotlin/Native 目标被跳过），
        // 这里用 findByName 安全跳过，避免整个 Gradle 配置阶段失败。
        findByName("iosMain")?.dependencies {
        }
    }
}

android {
    namespace = "mahjong.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "mahjong.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "1.0.14"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )
    }
}

/**
 * 出包统一落地：每次 assembleDebug 之后把 APK 复制到仓库根目录 dist/。
 * 文件名里的版本号只从上面 defaultConfig 读，脚本里不再另写一份；
 * 同名文件直接覆盖并打印实际版本，避免 dist 落后于 build 目录。
 */
val distDebugApk by tasks.registering {
    // 每次都要真的执行：assembleDebug 命中 UP-TO-DATE 时也要把产物刷新到 dist。
    outputs.upToDateWhen { false }
    doLast {
        val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apkFile.isFile) { "未找到 APK：${apkFile.absolutePath}" }
        val versionName = android.defaultConfig.versionName
            ?: error("defaultConfig.versionName 未设置")
        val versionCode = android.defaultConfig.versionCode
        val target = rootProject.layout.projectDirectory
            .file("dist/zhujian-mahjong-$versionName-debug.apk").asFile
        val existed = target.isFile
        target.parentFile.mkdirs()
        apkFile.copyTo(target, overwrite = true)
        val mib = apkFile.length() / (1024.0 * 1024.0)
        // 用 ASCII 输出：Windows 终端常是 GBK 码页，中文会变乱码。
        println(
            "dist: ${target.name} (versionName=$versionName, versionCode=$versionCode, " +
                "%.2f MiB%s)".format(mib, if (existed) ", overwritten" else "")
        )
    }
}

// AGP 的 assembleDebug 在插件应用后才创建，这里延迟绑定，避免配置期取不到任务。
tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(distDebugApk)
}
