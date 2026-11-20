@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mahjong.app.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectMake
import platform.darwin.NSObject
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.coroutines.resume

/**
 * iOS 侧更新通道：隐藏 WKWebView 渲染分享页读文件名，再抓一次下载直链交 Safari。
 *
 * iOS 没有「应用内直接装包」的权限（未签名 IPA 只能靠侧载），所以这里只负责
 * 把最新版本告诉用户，并让浏览器把安装包下载到「文件」App。
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

/** 分享页自己的域名：这些跳转放行，其它跳转视为网盘下发的下载直链。 */
private const val SHARE_ORIGIN = "https://share.feijipan.com"

private var web: WKWebView? = null
private var linkSink: ((String?) -> Unit)? = null

/**
 * 点文件行 → 点下载按钮后，页面会跳到带签名的下载直链。
 * 在导航决策阶段截住它：取消 WebView 导航，把 URL 交给上层用 Safari 打开。
 */
private val navDelegate = object : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val sink = linkSink
        if (sink != null && url != null && url.startsWith("http") && !url.startsWith(SHARE_ORIGIN)) {
            linkSink = null
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            sink(url)
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
    }
}

actual fun platformUpdateFolder(): String = "ios"

actual fun platformUpdateExt(): String = "ipa"

actual suspend fun platformReadShareFolder(): FeedResult = withContext(Dispatchers.Main) {
    openPlatformFolder(ensureWebView())
}

/** 打开分享页并进入平台文件夹，返回目录里的文本行。 */
private suspend fun openPlatformFolder(view: WKWebView): FeedResult {
    val folder = platformUpdateFolder()

    // loadRequest 是异步的：上一次检查可能把 WebView 留在目录页，这时读文本和点击
    // 都落在旧文档上。先切 about:blank 把旧文档卸掉。
    NSURL.URLWithString("about:blank")?.let {
        view.loadRequest(NSURLRequest.requestWithURL(it))
    }
    awaitText(view, 3) { it.isBlank() }

    val url = NSURL.URLWithString(SHARE_URL)
        ?: return FeedResult.Failed("分享地址无效。")
    view.loadRequest(NSURLRequest.requestWithURL(url))

    // 表头先于文件行渲染，等文件夹行真正出现再点，否则点击落空。
    val root = awaitText(view, 42) { t -> t.contains("文件名") && t.contains(folder) }
        ?: return FeedResult.Failed("打开分享页超时，请检查网络后重试。")

    if (evalScript(view, clickByTextJs(folder)) != "ok") {
        return FeedResult.Failed("分享页里没找到「$folder」文件夹，请确认分享结构。")
    }
    val text = awaitFolderListing(view, root)
        ?: return FeedResult.Failed("进入「$folder」文件夹超时，请稍后重试。")

    return FeedResult.Ok(text.split('\n'))
}

/**
 * 等目录页出结果：出现安装包行立刻返回；没有安装包行时，等文本稳定约 1.8s
 * 按空目录返回，交给上层提示「分享目录里还没有安装包」。
 */
private suspend fun awaitFolderListing(
    view: WKWebView,
    root: String,
    attempts: Int = 34,
): String? {
    var last = ""
    var stable = 0
    repeat(attempts) {
        val text = evalText(view)
        if (text.contains(".apk", ignoreCase = true) || text.contains(".ipa", ignoreCase = true)) {
            return text
        }
        if (text.isNotBlank() && text != root && text == last) {
            stable++
            if (stable >= 3) return text
        } else {
            stable = 0
        }
        last = text
        delay(600)
    }
    return null
}

actual suspend fun platformStartUpdateInstall(pkg: RemotePackage): String = withContext(Dispatchers.Main) {
    val view = ensureWebView()
    // 「下载更新」可能距上次检测已经过了一段时间，WebView 未必还停在包列表页。
    if (!evalText(view).contains(pkg.fileName)) {
        val listing = openPlatformFolder(view)
        if (listing is FeedResult.Failed) {
            platformOpenSharePage()
            return@withContext "${listing.message}已用浏览器打开分享页。"
        }
    }

    val link = resolveDownloadLink(view, pkg.fileName)
    if (link.isNullOrBlank()) {
        platformOpenSharePage()
        return@withContext "没能拿到下载直链，已用浏览器打开分享页。"
    }
    openInBrowser(link)
    "已用浏览器打开下载链接，完成后请到「文件 → 下载」里侧载安装。"
}

actual fun platformOpenSharePage() {
    openInBrowser(SHARE_URL)
}

private fun openInBrowser(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
}

private fun ensureWebView(): WKWebView {
    web?.let { return it }
    val config = WKWebViewConfiguration()
    config.defaultWebpagePreferences.allowsContentJavaScript = true
    // 尺寸给足：分享页按宽度做响应式布局，视角太窄会把文件行整行隐藏。
    val view = WKWebView(frame = CGRectMake(0.0, 0.0, 1024.0, 768.0), configuration = config)
    view.customUserAgent = DESKTOP_UA
    view.navigationDelegate = navDelegate
    // alpha=0 + 不接收触摸：挂到窗口只是为了布局渲染，用户看不见也点不到。
    view.alpha = 0.0
    view.userInteractionEnabled = false
    hostWindow()?.addSubview(view)
    web = view
    return view
}

/** Scene 化之后 keyWindow 可能为空，兜底取第一个窗口。 */
private fun hostWindow(): UIWindow? {
    val app = UIApplication.sharedApplication
    app.keyWindow?.let { return it }
    return app.windows.filterIsInstance<UIWindow>().firstOrNull()
}

/**
 * 选中文件行 → 等下载按钮出现 → 点 .down-btn，由 navigationDelegate 截下直链。
 * 直链带签名会过期，所以每次都现取，绝不缓存。
 */
private suspend fun resolveDownloadLink(view: WKWebView, fileName: String): String? =
    withTimeoutOrNull(15_000) {
        val deferred = CompletableDeferred<String?>()
        linkSink = { url -> if (!deferred.isCompleted) deferred.complete(url) }
        try {
            if (evalScript(view, clickByTextJs(fileName)) != "ok") {
                deferred.complete(null)
            } else {
                delay(1_200)
                if (evalScript(
                        view,
                        "var b=document.querySelector('.down-btn'); if(!b){'no'} else { b.click(); 'ok' }",
                    ) != "ok"
                ) {
                    deferred.complete(null)
                }
            }
            deferred.await()
        } finally {
            // 超时被取消时也要摘掉回调，避免后续导航被误当成下载直链。
            linkSink = null
        }
    }

/** 轮询页面文本（每 600ms 一次，最多 attempts 次），直到满足条件。 */
private suspend fun awaitText(
    view: WKWebView,
    attempts: Int,
    predicate: (String) -> Boolean,
): String? {
    repeat(attempts) {
        val text = evalText(view)
        if (predicate(text)) return text
        delay(600)
    }
    return null
}

private suspend fun evalText(view: WKWebView): String =
    evalScript(view, "document.body ? document.body.innerText : ''")

private suspend fun evalScript(view: WKWebView, script: String): String =
    suspendCancellableCoroutine { cont ->
        view.evaluateJavaScript(script) { result, _ ->
            if (cont.isActive) cont.resume((result as? String) ?: "")
        }
    }
