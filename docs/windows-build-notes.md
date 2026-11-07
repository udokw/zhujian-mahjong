# Windows 聚合构建问题说明

本文记录在 Windows + 中文用户名（`狂客`）环境下运行根目录 Gradle 构建时遇到的两个问题，供界面/安卓会话修复聚合构建时参考。

## 1. `app` 模块引用不存在的 `iosMain` 源集

**现象**

```
Source set with name 'iosMain' not found.
```

配置阶段即失败，导致 `:core`、`:cli` 也无法构建。

**原因**

- `gradle.properties` 中设置了 `kotlin.native.ignoreDisabledTargets=true`。
- Windows 主机上 iOS 目标不可用，Kotlin 插件不会创建 `iosX64Main` / `iosArm64Main` 等源集。
- `app/build.gradle.kts` 里使用 `val iosMain by getting { ... }`，在源集不存在时直接抛错。

**建议修法**

- 用 `by creating` 替代 `by getting`，或把 iOS 源集配置放进 `if (targets.any { it.name.startsWith("ios") })` 判断中；
- 若只需在 Windows 上构建 Android，可把 iOS target 与 `iosMain` 配置移到独立的 KMP 模块，避免 Android 模块在 Windows 上被 iOS 配置拖死。

## 2. JDK 17 按 GBK 解码 `@argfile`，撞中文用户名

**现象**

```
ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
```

Gradle 正常启动、任务正常执行，但测试 worker 进程找不到主类。

**原因**

- Gradle 把 worker 的 classpath 写入临时 `@argfile`，内容为 UTF-8。
- JDK 17 的 `java` 启动器按系统 ANSI（简体中文 Windows 为 GBK）解码 argfile。
- 用户名「狂客」的 UTF-8 字节被错误解码，`C:\Users\狂客\.gradle\...` 变成乱码路径，classpath 失效。
- 与本项目代码无关，升级到 JDK 18+ 后 argfile 默认按 UTF-8 读取，问题消失。

**临时绕过（已验证）**

1. 用 `mklink /J C:\Users\Public\gradlehome C:\Users\狂客\.gradle` 建一个纯 ASCII 的 Gradle 用户目录，并设置 `GRADLE_USER_HOME` 指向它；
2. 用 `mklink /J C:\Users\Public\mjproj G:\AI\麻将` 建工程目录的 ASCII 映射；
3. 通过独立 settings 文件只构建 `:core` / `:cli`，并用 init script 把 `buildDirectory` 指到 `C:/Users/Public/mjbuild/<name>`，避免与并行构建争用 `core/build`。

示例：

```powershell
$env:GRADLE_USER_HOME='C:\Users\Public\gradlehome'
.\gradlew.bat -c C:\Users\Public\mjbuild\engine-settings.gradle.kts `
  --init-script "$env:TEMP\mj-agent-isolate.gradle" :core:jvmTest :cli:installDist --no-daemon
```

**正式修法**

- 聚合构建统一使用 JDK 21（或 18+）作为 Gradle JVM；
- 或把 `GRADLE_USER_HOME` 固定到纯 ASCII 路径（如 `C:\gradle-home`）；
- 不要让两个根工程共享同一个 `core/build` 目录。

## 3. 已做的兼容改动

- `gradle.properties` 增加 `android.overridePathCheck=true`，仅为关闭 Android 插件对非 ASCII 工程路径的强制检查。
- 该开关不改变构建行为，但 Android 插件仍可能因路径包含中文出现其他工具链问题，正式构建建议仍使用 ASCII 路径。
