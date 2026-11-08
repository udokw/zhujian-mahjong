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

// 崩溃前预分配的缓冲区：信号处理器里不能做内存分配，所以安装时就把
// 「上一条启动阶段」和「SIGNAL 前缀」写进固定大小的 C 缓冲区，崩溃时直接 write。
private var gBufA: UnsafeMutablePointer<CChar>?
private var gBufB: UnsafeMutablePointer<CChar>?
private var gWhich: Int32 = 0
private var gSignalLine: UnsafeMutablePointer<CChar>?
private var gCrashFd: Int32 = -1
private let gSignalPrefix: [CChar] = [83, 73, 71, 78, 65, 76, 32] // "SIGNAL "

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

/// 信号处理器里只做：写出预分配的 stage 字符串 + 信号编号，fsync，然后把默认行为还回去，
/// 让系统照常生成 .ips 崩溃报告。
private func handleSignal(_ sig: Int32) {
    if gCrashFd >= 0 {
        if let buffer = (gWhich == 0 ? gBufA : gBufB) {
            _ = write(gCrashFd, buffer, Int(strlen(buffer)))
        }
        if let line = gSignalLine {
            var index = 0
            for char in gSignalPrefix {
                line[index] = char
                index += 1
            }
            var value = sig
            if value >= 10 {
                line[index] = CChar(48 + value / 10)
                index += 1
                value = value % 10
            }
            line[index] = CChar(48 + value)
            index += 1
            line[index] = 10
            index += 1
            line[index] = 0
            _ = write(gCrashFd, line, index)
        }
        _ = fsync(gCrashFd)
    }
    signal(sig, SIG_DFL)
    _ = kill(getpid(), sig)
}

/// 启动诊断日志。
///
/// 目标：App 在首帧阶段崩溃、用户手边又没有 Mac / Xcode 时，仍能把「崩在哪一步」取出来。
/// 做法分两层：
/// 1. 每个启动阶段（stage）立刻写进 UserDefaults 并 fsync 到 `Documents/crash.log`；
/// 2. 进程被信号打死或抛 NSException 时，用预分配缓冲区再补一条记录。
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
        allocateSignalBuffers()
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
        refreshSignalBuffer("last-stage=\(stage) at \(time)\n")
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

    private static func allocateSignalBuffers() {
        guard gBufA == nil else { return }
        _ = gSignalPrefix // 提前初始化这个全局常量，避免首次访问发生在信号处理器里
        let a = UnsafeMutablePointer<CChar>.allocate(capacity: 512)
        let b = UnsafeMutablePointer<CChar>.allocate(capacity: 512)
        let line = UnsafeMutablePointer<CChar>.allocate(capacity: 64)
        a.initialize(repeating: 0, count: 512)
        b.initialize(repeating: 0, count: 512)
        line.initialize(repeating: 0, count: 64)
        gBufA = a
        gBufB = b
        gSignalLine = line
    }

    private static func refreshSignalBuffer(_ text: String) {
        guard let a = gBufA, let b = gBufB else { return }
        let next = gWhich == 0 ? b : a
        text.withCString { source in
            var index = 0
            while index < 511 && source[index] != 0 {
                next[index] = source[index]
                index += 1
            }
            next[index] = 0
        }
        gWhich = gWhich == 0 ? 1 : 0
    }
}
