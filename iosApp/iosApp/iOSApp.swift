import SwiftUI

@main
struct iOSApp: App {
    init() {
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
