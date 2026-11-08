import Darwin
import Foundation

// 底层日志实现刻意放在文件级（而不是 CrashLogger 的静态成员）：
// NSSetUncaughtExceptionHandler 的回调必须是 C 函数指针，闭包里只要引用了
// 类型成员就会被判定为「捕获上下文」而编译失败，只能引用全局符号。

private let gLogFileName = "crash.log"
private let gStageKey = "diag.lastStage"
private let gStageTimeKey = "diag.lastStageTime"
private let gPendingKey = "diag.pendingReport"
private let gExceptionKey = "diag.pendingException"
private let gKotlinCrashKey = "diag.pendingKotlinCrash"

private let gWriteQueue = DispatchQueue(label: "mahjong.crash-logger")
private let gTimeFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    formatter.locale = Locale(identifier: "en_US_POSIX")
    return formatter
}()

/// 信号处理器里不能分配内存，前缀写成固定字节数组，崩溃时直接 write。
private let gSignalWord: [CChar] = [83, 73, 71, 78, 65, 76, 32] // "SIGNAL "
private var gCrashFd: Int32 = -1

private func gDocumentsDirectory() -> String {
    NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first
        ?? NSTemporaryDirectory()
}

private func gLogPath() -> String {
    gDocumentsDirectory() + "/" + gLogFileName
}

private func gNow() -> String {
    gTimeFormatter.string(from: Date())
}

private func gAppendLine(_ line: String) {
    let fd = gCrashFd
    guard fd >= 0 else { return }
    let text = line.hasSuffix("\n") ? line : line + "\n"
    gWriteQueue.sync {
        text.withCString { pointer in
            _ = write(fd, pointer, Int(strlen(pointer)))
        }
        _ = fsync(fd)
    }
}

private func gTail(lines count: Int) -> String {
    guard let content = try? String(contentsOfFile: gLogPath(), encoding: .utf8),
          !content.isEmpty else {
        return "（还没有日志）"
    }
    return content.components(separatedBy: "\n").suffix(count).joined(separator: "\n")
}

/// 信号处理器只做三件事：补一条 `SIGNAL n`、fsync、把默认行为还回去，
/// 让系统照常生成 .ips 崩溃报告。真正「崩在哪一步」由紧挨在它上面的 STAGE 行回答。
private func handleSignal(_ sig: Int32) {
    let fd = gCrashFd
    if fd >= 0 {
        gSignalWord.withUnsafeBufferPointer { pointer in
            if let base = pointer.baseAddress {
                _ = write(fd, base, pointer.count)
            }
        }
        var value = sig
        if value >= 10 {
            var tens = CChar(48 + (value / 10) % 10)
            _ = write(fd, &tens, 1)
            value = value % 10
        }
        var units = CChar(48 + value)
        _ = write(fd, &units, 1)
        var newLine: CChar = 10
        _ = write(fd, &newLine, 1)
        _ = fsync(fd)
    }
    signal(sig, SIG_DFL)
    _ = kill(getpid(), sig)
}

/// 启动诊断日志。
///
/// 目标：App 在首帧阶段崩溃、用户手边又没有 Mac / Xcode 时，仍能把「崩在哪一步」取出来。
/// 做法分两层：
/// 1. 每个启动阶段（stage）立刻写进 UserDefaults 并 fsync 到 `Documents/crash.log`；
/// 2. 进程被信号打死或抛 NSException 时，再补一条记录。
///
/// 下次启动时 SwiftUI 会抢在 Compose 之前把上一次的记录显示出来，用户可一键复制。
enum CrashLogger {
    static var logPath: String { gLogPath() }

    private static var versionText: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short)(\(build))"
    }

    // MARK: - 安装

    static func install() {
        _ = gSignalWord // 提前初始化这个全局常量，避免首次访问发生在信号处理器里
        trimIfNeeded()
        openLog()

        let defaults = UserDefaults.standard
        let previousStage = defaults.string(forKey: gStageKey)
        let previousTime = defaults.string(forKey: gStageTimeKey)
        defaults.removeObject(forKey: gStageKey)

        // 只有「上一次没走到 ready」或「上一次留下了异常」才准备诊断页。
        var sections: [String] = []
        if let previousStage = previousStage, previousStage != "ready" {
            sections.append("上次运行停在阶段：\(previousStage)（\(previousTime ?? "时间未知")）")
        }
        if let exception = defaults.string(forKey: gExceptionKey), !exception.isEmpty {
            sections.append("Objective-C 异常：\n\(exception)")
            defaults.removeObject(forKey: gExceptionKey)
        }
        if let kotlin = defaults.string(forKey: gKotlinCrashKey), !kotlin.isEmpty {
            sections.append("Kotlin 未捕获异常：\n\(kotlin)")
            defaults.removeObject(forKey: gKotlinCrashKey)
        }
        if sections.isEmpty {
            defaults.removeObject(forKey: gPendingKey)
        } else {
            sections.append("—— crash.log 末尾 ——\n\(gTail(lines: 160))")
            defaults.set(sections.joined(separator: "\n\n"), forKey: gPendingKey)
        }
        defaults.synchronize()

        gAppendLine("===== LAUNCH \(versionText) \(gNow()) =====")
        mark("swift.install")
        installHandlers()
    }

    /// 记录一个启动阶段。会同步落盘，所以崩溃时最后一条 stage 一定已经在磁盘上。
    static func mark(_ stage: String) {
        let time = gNow()
        let defaults = UserDefaults.standard
        defaults.set(stage, forKey: gStageKey)
        defaults.set(time, forKey: gStageTimeKey)
        defaults.synchronize()
        gAppendLine("STAGE \(time) \(stage)")
    }

    // MARK: - 读取 / 清理

    static func pendingReport() -> String? {
        guard let text = UserDefaults.standard.string(forKey: gPendingKey), !text.isEmpty else {
            return nil
        }
        return text
    }

    static func clearPendingReport() {
        UserDefaults.standard.removeObject(forKey: gPendingKey)
        UserDefaults.standard.synchronize()
    }

    // MARK: - 日志文件

    private static func openLog() {
        let path = gLogPath()
        if !FileManager.default.fileExists(atPath: path) {
            _ = FileManager.default.createFile(atPath: path, contents: nil)
        }
        if gCrashFd >= 0 {
            _ = close(gCrashFd)
        }
        gCrashFd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
    }

    private static func trimIfNeeded() {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: gLogPath()),
              let size = (attributes[.size] as? NSNumber)?.intValue,
              size > 512 * 1024 else {
            return
        }
        try? ("……（较早日志已截断）\n" + gTail(lines: 400))
            .write(toFile: gLogPath(), atomically: true, encoding: .utf8)
    }

    // MARK: - 崩溃兜底

    private static func installHandlers() {
        // 回调是 C 函数指针，内部只能引用全局符号，不能捕获任何上下文。
        NSSetUncaughtExceptionHandler { exception in
            let detail = "\(exception.name.rawValue): \(exception.reason ?? "-")\n"
                + exception.callStackSymbols.joined(separator: "\n")
            gAppendLine("EXCEPTION \(detail)")
            let defaults = UserDefaults.standard
            defaults.set(detail, forKey: gExceptionKey)
            defaults.synchronize()
        }

        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP] {
            signal(sig, handleSignal)
        }
    }
}
