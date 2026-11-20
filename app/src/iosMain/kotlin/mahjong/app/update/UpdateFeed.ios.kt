@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mahjong.app.update

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLog
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.darwin.NSObject
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

/** 取链接口的域名：它只是 302 中转，绝不能把这个地址交给 Safari。 */
private const val API_HOST = "api.feijipan.com"

private const val LOG_PREFIX = "[ZJMJ-Update]"

/** 统一打点：Windows 侧没有 idevicesyslog，真机主要靠 App 内提示与这条日志取证。 */
private fun iLog(message: String) {
    NSLog("%@ %@", LOG_PREFIX, message)
}

/** 解析结果：成功带 CDN 直链，失败带可读原因（含 HTTP 状态与响应片段）。 */
private sealed interface ResolveResult {
    data class Ok(val url: String) : ResolveResult
    data class Failed(val reason: String) : ResolveResult
}

private var web: WKWebView? = null
private var linkSink: ((String?) -> Unit)? = null

/**
 * 页面点下载后会用 window.open(接口地址, "_self") 导航到取链接口（api.feijipan.com），
 * 由接口 302 到 CDN。这里只负责把「接口地址」交回上层，由上层在 App 内跟完 302；
 * 绝不能直接把地址交给 Safari —— 裸请求缺 Referer/UA/Cookie 会被判「参数有误」。
 */
private val navDelegate = object : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val sink = linkSink
        if (sink != null && url != null) {
            iLog("navigation during capture: $url")
        }
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
            iLog("open folder failed: ${listing.message}")
            platformOpenSharePage()
            return@withContext "${listing.message}已用浏览器打开分享页。"
        }
    }

    val apiUrl = resolveDownloadLink(view, pkg.fileName)
    if (apiUrl.isNullOrBlank()) {
        iLog("capture failed: webview gave no url for ${pkg.fileName}")
        platformOpenSharePage()
        return@withContext "没能拿到下载链接（页面没有触发下载），已用浏览器打开分享页。"
    }
    iLog("captured api url: $apiUrl")

    // 关键：接口地址只留在 App 内跟 302，拿到 CDN 直链才交给 Safari。
    val resolved = resolveFinalUrl(apiUrl)
    when (resolved) {
        is ResolveResult.Ok -> {
            if (resolved.url.contains(API_HOST) || resolved.url == apiUrl) {
                // 兜底：无论如何都不把接口地址交出去。
                iLog("resolved still api url, abort: ${resolved.url}")
                platformOpenSharePage()
                return@withContext "接口没有返回跳转地址，已用浏览器打开分享页。\n接口地址：${clip(apiUrl)}"
            }
            iLog("open browser: ${resolved.url}")
            openInBrowser(resolved.url)
            "已用浏览器打开下载链接，完成后请到「文件 → 下载」里侧载安装。"
        }

        is ResolveResult.Failed -> {
            iLog("resolve failed: ${resolved.reason} | api=$apiUrl")
            platformOpenSharePage()
            "取下载直链失败：${resolved.reason}\n接口地址：${clip(apiUrl)}\n已用浏览器打开分享页。"
        }
    }
}

/** 提示里放完整地址太长，截断但保留主机与参数开头，够真机判断是接口还是 CDN。 */
private fun clip(url: String, max: Int = 220): String =
    if (url.length <= max) url else url.take(max) + "…"

/**
 * 在 App 内把接口地址跟完 302，取出 CDN 真实直链。
 *
 * 取链接口本身会 302 到 dl-*.feejii.com 的带签名地址；直接把它扔给 Safari，
 * 裸请求没有 Referer / 桌面 UA / cookie，会被判「参数有误」并返回一段 JSON。
 * 接口可能连跳两三层，这里最多跟 3 跳，落到非接口域名就收手。
 */
private suspend fun resolveFinalUrl(apiUrl: String): ResolveResult {
    var current = apiUrl
    var last: ResolveResult = ResolveResult.Failed("未知错误")
    repeat(3) {
        val result = withTimeoutOrNull(20_000) { requestOnce(current) }
            ?: ResolveResult.Failed("解析下载地址超时")
        last = result
        if (result !is ResolveResult.Ok) return result
        if (!result.url.contains(API_HOST)) return result
        if (result.url == current) return ResolveResult.Failed("接口反复跳回自己：${clip(current)}")
        current = result.url
    }
    return last
}

/**
 * 发一次请求并只看跳转：带桌面 UA、分享页 Referer/Origin 与 WKWebView 的 cookie；
 * 在重定向回调里只记 Location 并终止跟随，绝不把上百 MB 安装包读进内存；
 * 接口若直接回 JSON，就从响应体里正则提取真实下载地址。
 */
private suspend fun requestOnce(apiUrl: String): ResolveResult {
    val nsUrl = NSURL.URLWithString(apiUrl)
        ?: return ResolveResult.Failed("接口地址无效")
    val request = NSMutableURLRequest.requestWithURL(nsUrl)
    request.HTTPMethod = "GET"
    request.timeoutInterval = 15.0
    request.setValue(DESKTOP_UA, forHTTPHeaderField = "User-Agent")
    request.setValue("https://share.feijipan.com/", forHTTPHeaderField = "Referer")
    request.setValue("https://share.feijipan.com", forHTTPHeaderField = "Origin")
    request.setValue("application/json, text/plain, */*", forHTTPHeaderField = "Accept")
    val cookie = webCookies()
    if (cookie.isNotBlank()) {
        iLog("attach cookies: ${cookie.take(160)}")
        request.setValue(cookie, forHTTPHeaderField = "Cookie")
    }

    val config = NSURLSessionConfiguration.ephemeralSessionConfiguration()
    config.timeoutIntervalForRequest = 15.0
    config.timeoutIntervalForResource = 20.0

    return suspendCancellableCoroutine { cont ->
        val probe = RedirectProbe(cont)
        val session = NSURLSession.sessionWithConfiguration(config, probe, NSOperationQueue())
        probe.session = session
        val task = session.dataTaskWithRequest(request)
        cont.invokeOnCancellation {
            task.cancel()
            session.invalidateAndCancel()
        }
        task.resume()
    }
}

/** WKWebView 里的 cookie 与 NSHTTPCookieStorage 不是一套，必须从网页数据仓库取。 */
private suspend fun webCookies(): String {
    val store = web?.configuration?.websiteDataStore?.httpCookieStore ?: return ""
    return suspendCancellableCoroutine { cont ->
        store.getAllCookies { cookies ->
            val header = cookies.orEmpty()
                .filterIsInstance<NSHTTPCookie>()
                .joinToString("; ") { "${it.name}=${it.value}" }
            if (cont.isActive) cont.resume(header)
        }
    }
}

/**
 * 只跟一层跳转的探针：拿到 3xx 的 Location 就停，或读到 JSON 响应体后提取地址。
 * 全程不把安装包内容留在内存里（非文本响应在收到响应头时就取消）。
 */
private class RedirectProbe(
    private var cont: CancellableContinuation<ResolveResult>?,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    var session: NSURLSession? = null

    private var location: String? = null
    private var statusCode = 0
    private var mime = ""
    private var bodyText = ""
    private val body = NSMutableData()
    private var done = false

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        val target = newRequest.URL?.absoluteString
        iLog("redirect ${willPerformHTTPRedirection.statusCode} -> $target")
        if (location == null) location = target
        // 传 null：不跟随跳转，避免把整个安装包读进内存。
        completionHandler(null)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val http = didReceiveResponse as? NSHTTPURLResponse
        statusCode = http?.statusCode?.toInt() ?: 0
        mime = didReceiveResponse.MIMEType.orEmpty()
        val responseUrl = didReceiveResponse.URL?.absoluteString
        iLog("response $statusCode mime=$mime url=$responseUrl")

        val header = http?.let { headerValue(it, "Location") }
        if (!header.isNullOrBlank()) {
            iLog("location header: $header")
            location = header
            completionHandler(NSURLSessionResponseDisposition.NSURLSessionResponseCancel)
            finish(ResolveResult.Ok(absolutize(header, http?.URL)))
            return
        }

        val textLike = mime.isEmpty() ||
            mime.contains("json", ignoreCase = true) ||
            mime.contains("text", ignoreCase = true) ||
            mime.contains("html", ignoreCase = true)
        if (!textLike && statusCode in 200..299) {
            // 已经是文件响应：不读内容，当前地址就是直链。
            completionHandler(NSURLSessionResponseDisposition.NSURLSessionResponseCancel)
            finish(
                if (responseUrl.isNullOrBlank()) {
                    ResolveResult.Failed("服务器返回了文件但地址为空")
                } else {
                    ResolveResult.Ok(responseUrl)
                },
            )
            return
        }
        completionHandler(NSURLSessionResponseDisposition.NSURLSessionResponseAllow)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        if (body.length.toLong() < 64 * 1024) body.appendData(didReceiveData)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        if (done) return
        bodyText = body.utf8().orEmpty()
        if (bodyText.isNotBlank()) {
            iLog("body ${bodyText.length}: ${bodyText.take(240).replace('\n', ' ')}")
        }
        if (didCompleteWithError != null && location == null && statusCode == 0) {
            finish(
                ResolveResult.Failed(
                    "请求失败：${didCompleteWithError.localizedDescription}（${didCompleteWithError.code}）"
                )
            )
            return
        }

        val fromBody = extractUrl(bodyText)
        if (fromBody != null) iLog("body url: $fromBody")
        val candidate = fromBody ?: location
        if (candidate.isNullOrBlank()) {
            val snippet = bodyText.take(160).replace('\n', ' ')
            finish(
                ResolveResult.Failed("接口没有给出跳转地址（HTTP $statusCode，$mime）$snippet")
            )
            return
        }
        finish(ResolveResult.Ok(absolutize(candidate, task.response?.URL)))
    }

    private fun finish(result: ResolveResult) {
        if (done) return
        done = true
        val c = cont
        cont = null
        val s = session
        session = null
        s?.invalidateAndCancel()
        if (c != null && c.isActive) c.resume(result)
    }
}

private fun headerValue(response: NSHTTPURLResponse, name: String): String? =
    response.allHeaderFields.entries
        .firstOrNull { (it.key as? String)?.equals(name, ignoreCase = true) == true }
        ?.value as? String

private val UrlInText = Regex("""https?://[^\s"'<>\\]+""")

/** 接口返回 JSON 时，从响应体里挑出真正的下载地址（跳过接口自身与分享页）。 */
private fun extractUrl(text: String): String? {
    if (text.isBlank()) return null
    val normalized = text
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
    val urls = UrlInText.findAll(normalized)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
        .filter { !it.contains(API_HOST) && !it.contains("share.feijipan.com") }
        .toList()
    return urls.firstOrNull { it.contains("sign=") || it.contains("dl-") || it.contains("feejii") }
        ?: urls.firstOrNull()
}

private fun absolutize(raw: String, base: NSURL?): String {
    if (raw.startsWith("http", ignoreCase = true)) return raw
    return base?.let { NSURL.URLWithString(raw, relativeToURL = it)?.absoluteString } ?: raw
}

private fun NSData.utf8(): String? =
    NSString.create(data = this, encoding = NSUTF8StringEncoding) as String?

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
