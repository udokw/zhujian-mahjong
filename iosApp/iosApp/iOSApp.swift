import SwiftUI
import Darwin

@main
struct iOSApp: App {
    init() {
        // 往已断开的 socket 写数据时，内核默认发 SIGPIPE 直接杀进程（不产生崩溃报告）。
        // 各连接已设 SO_NOSIGPIPE，这里再全局兜底一次。
        signal(SIGPIPE, SIG_IGN)
        // 尽早装上崩溃兜底：之后每次启动阶段都会落盘到 Documents/crash.log。
        CrashLogger.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
                .preferredColorScheme(nil)
        }
    }
}
