package mahjong.app.update

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mahjong.app.platformAppVersion
import mahjong.app.ui.DialogMessage
import mahjong.app.ui.DialogTitle
import mahjong.app.ui.GhostButton
import mahjong.app.ui.MjDialog
import mahjong.app.ui.PrimaryButton

/**
 * 更新检查的界面状态中枢。
 *
 * 检测本身在平台层（隐藏 WebView 渲染分享页），这里只负责：
 * 手动/启动检测的节流、结果提示、以及「发现新版本」小对话框的开关。
 */
object UpdateCenter {
    /** 正在检测或正在下载。 */
    var busy by mutableStateOf(false)
        private set

    /** 非空时显示「发现新版本」小对话框。 */
    var prompt by mutableStateOf<RemotePackage?>(null)
        private set

    /** 最近一次检查结论，显示在设置页。 */
    var status by mutableStateOf("")
        private set

    private var scope: CoroutineScope? = null
    private var notify: ((String) -> Unit)? = null
    private var autoChecked = false

    fun attach(scope: CoroutineScope, notify: (String) -> Unit) {
        this.scope = scope
        this.notify = notify
    }

    /** 设置页点「版本」或「检查更新」：无论开关是否打开都真的去查一次。 */
    fun check() {
        if (busy) return
        val scope = scope ?: return
        scope.launch {
            busy = true
            status = "正在检查…"
            val result = runCatching { checkForUpdate() }
                .getOrElse { UpdateCheckResult.Failed(platformAppVersion(), it.message ?: "检查更新失败") }
            busy = false
            when (result) {
                is UpdateCheckResult.NewVersion -> {
                    status = "发现新版本 v${result.pkg.version}"
                    prompt = result.pkg
                }

                is UpdateCheckResult.UpToDate -> {
                    status = "已是最新版本"
                    notify?.invoke("当前已是最新版本 v${result.currentVersion}")
                }

                is UpdateCheckResult.NoPackage -> {
                    status = "分享目录里还没有安装包"
                    notify?.invoke("分享目录里还没有安装包，请稍后再试")
                }

                is UpdateCheckResult.Failed -> {
                    status = result.message
                    notify?.invoke(result.message)
                }
            }
        }
    }

    /** 冷启动静默检查一次：只在新版本出现时弹窗，其它情况一律不打扰。 */
    fun checkOnStart(enabled: Boolean) {
        if (!enabled || autoChecked) return
        autoChecked = true
        val scope = scope ?: return
        scope.launch {
            val result = runCatching { checkForUpdate() }.getOrNull() ?: return@launch
            if (result is UpdateCheckResult.NewVersion) {
                status = "发现新版本 v${result.pkg.version}"
                prompt = result.pkg
            }
        }
    }

    fun dismiss() {
        if (busy) return
        prompt = null
    }

    /** 下载并安装。安卓走 DownloadManager + 系统安装器，iOS 交系统浏览器下载。 */
    fun install() {
        val pkg = prompt ?: return
        val scope = scope ?: return
        scope.launch {
            busy = true
            val message = runCatching { platformStartUpdateInstall(pkg) }
                .getOrElse { it.message ?: "下载更新失败，请稍后重试" }
            busy = false
            prompt = null
            status = message
            notify?.invoke(message)
        }
    }
}

/** 「发现新版本」小对话框：全局只挂一次，任何界面检测到新版都能弹出来。 */
@Composable
fun UpdatePromptHost() {
    val pkg = UpdateCenter.prompt ?: return
    val ios = platformUpdateExt() == "ipa"
    MjDialog(onDismiss = { UpdateCenter.dismiss() }, maxWidth = 340.dp) {
        DialogTitle("发现新版本 v${pkg.version}")
        Spacer(Modifier.height(6.dp))
        DialogMessage(
            if (ios) {
                "当前 v${platformAppVersion()} → 最新 v${pkg.version}\n点击后由浏览器下载安装包，未签名 IPA 需自行侧载。"
            } else {
                "当前 v${platformAppVersion()} → 最新 v${pkg.version}\n下载完成后会自动调起系统安装器。"
            }
        )
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            label = if (UpdateCenter.busy) "处理中…" else "下载更新",
            enabled = !UpdateCenter.busy,
            height = 46.dp,
            onClick = { UpdateCenter.install() },
        )
        Spacer(Modifier.height(6.dp))
        GhostButton(
            label = "稍后",
            enabled = !UpdateCenter.busy,
            height = 42.dp,
            onClick = { UpdateCenter.dismiss() },
        )
    }
}
