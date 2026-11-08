import SwiftUI
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        CrashLogger.mark("swift.compose.create")
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// 纯 SwiftUI 的诊断页：不挂 Compose，保证稳定可读。
/// 只有上一次启动异常中断时才会出现，用户可以把内容复制出来再继续。
struct DiagnosticView: View {
    let report: String
    let onContinue: () -> Void

    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("竹间麻将 · 启动诊断")
                .font(.headline)
            Text("上一次启动没有正常完成。下面是落盘记录，点「复制」后可以粘贴到备忘录或直接发出来。")
                .font(.footnote)
                .foregroundColor(.secondary)

            ScrollView {
                Text(report)
                    .font(.system(size: 11, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(8)
            }
            .background(Color(red: 0.93, green: 0.93, blue: 0.93))
            .cornerRadius(8)

            HStack {
                Button(copied ? "已复制" : "复制诊断信息") {
                    UIPasteboard.general.string = report
                    copied = true
                }
                Spacer()
                Button("继续进入应用", action: onContinue)
            }
            .font(.subheadline)

            Text("日志文件：Documents/crash.log；也可以在「文件」App → 我的 iPhone → 竹间麻将 里查看。")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(16)
        .background(Color(red: 0.97, green: 0.96, blue: 0.94))
    }
}

struct ContentView: View {
    @State private var pendingReport: String? = CrashLogger.pendingReport()

    var body: some View {
        // 有上一次的诊断记录时，先用纯 SwiftUI 显示出来，不急着创建 Compose 视图，
        // 避免一进首帧又崩、用户来不及看。
        if let report = pendingReport {
            DiagnosticView(report: report) {
                CrashLogger.clearPendingReport()
                CrashLogger.mark("gate.continue")
                pendingReport = nil
            }
        } else {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
