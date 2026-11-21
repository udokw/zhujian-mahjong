@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package mahjong.app.update

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlin.coroutines.resume

/**
 * iOS 侧更新通道：直连接口取 CDN 直链，再交系统浏览器下载。
 *
 * iOS 没有「应用内直接装包」的权限（未签名 IPA 只能靠侧载），所以这里只负责
 * 把最新版本告诉用户，并让浏览器把安装包下载到「文件」App。
 */
actual fun platformUpdateFolder(): String = "ios"

actual fun platformUpdateExt(): String = "ipa"

// 走 POSIX gettimeofday：Kotlin/Native 的 NSDate 未导出 timeIntervalSince1970。
actual fun feijipanNowMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000L
}

/** 最近几条解析日志的内存轨迹：真机没有 syslog 时，拼进失败提示里取证。 */
private const val CRUMB_SLOTS = 6
private val crumbSlots = arrayOfNulls<String>(CRUMB_SLOTS)
private var crumbNext = 0

/**
 * 打点：只写内存环形轨迹，供失败提示回显取证。
 *
 * 千万不要在这里调 NSLog：真机崩溃日志证实，LiveContainer 环境下 os_log 的动态
 * 隐私探测会对 Kotlin 桥接字符串对象调 objc_opt_respondsToSelector，触发指针
 * 认证陷阱（EXC_BREAKPOINT / PAC DA）——点「下载更新」后一打点就闪退。
 */
private fun iLog(message: String) {
    runCatching {
        val slot = crumbNext
        crumbNext = (slot + 1) % CRUMB_SLOTS
        crumbSlots[slot] = message.take(160)
    }
}

/** 取证轨迹（最近几条，截断），拼进失败提示。自身绝不抛异常。 */
private fun crumbTrail(): String = runCatching {
    val idx = crumbNext
    (1..CRUMB_SLOTS).mapNotNull { k ->
        crumbSlots[(idx - k + CRUMB_SLOTS) % CRUMB_SLOTS]
    }.joinToString(" ｜ ")
}.getOrDefault("")

actual suspend fun feijipanHttp(url: String): HttpResult {
    val nsUrl = NSURL.URLWithString(url)
        ?: return HttpResult(0, null, "", "地址无效：${clipUrl(url)}")

    // 请求头统一挂在会话配置上：NSMutableURLRequest 的可变属性在 Kotlin/Native 里取不到。
    // 不要手动覆盖 Accept-Encoding：NSURLSession 默认带 gzip/deflate 并自动解压；
    // 接口只有在客户端声明支持压缩时才返回目录（否则 code=200 但 list 为空，2026-11-21 实测）。
    val config = NSURLSessionConfiguration.ephemeralSessionConfiguration()
    config.timeoutIntervalForRequest = 15.0
    config.timeoutIntervalForResource = 25.0
    config.HTTPAdditionalHeaders = mapOf<Any?, Any?>(
        "User-Agent" to DESKTOP_UA,
        "Referer" to "$SHARE_ORIGIN/",
        "Origin" to SHARE_ORIGIN,
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
    )
    val request = NSURLRequest.requestWithURL(nsUrl)

    var session: NSURLSession? = null
    return try {
        suspendCancellableCoroutine { cont ->
            val probe = HttpProbe(cont)
            val created = NSURLSession.sessionWithConfiguration(config, probe, NSOperationQueue())
            session = created
            iLog("GET ${clipUrl(url, 100)}")
            created.dataTaskWithURL(nsUrl).resume()
        }
    } finally {
        // 会话销毁不能在 delegate 回调栈里做：delegate 会被同步释放，真机确定性闪退。
        val current = session
        if (current != null) {
            dispatch_async(dispatch_get_main_queue()) { current.invalidateAndCancel() }
        }
    }
}

/**
 * 只跟一层跳转的探针：拿到 3xx 的 Location 就停，或读到 JSON 响应体。
 * 全程不把安装包内容留在内存里（非文本响应只取头部，不主动读大文件）。
 * 会话销毁交给 feijipanHttp 的 finally（回调栈之外）。
 */
private class HttpProbe(
    private var cont: CancellableContinuation<HttpResult>?,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private var status = 0
    private var location: String? = null
    private var body = ""
    private var done = false

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        try {
            if (status == 0) status = willPerformHTTPRedirection.statusCode.toInt()
            val target = newRequest.URL?.absoluteString
            iLog("redirect $status -> ${clipUrl(target.orEmpty(), 100)}")
            if (location.isNullOrBlank()) location = target
            // 传 null：不跟随跳转，避免把整个安装包读进内存。
            completionHandler(null)
        } catch (t: Throwable) {
            // 异常落进 ObjC 回调会直接杀进程：拦住并兜底调一次 completionHandler。
            iLog("redirect cb error: ${t.message}")
            runCatching { completionHandler(null) }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        try {
            val http = dataTask.response as? NSHTTPURLResponse
            if (http != null && status == 0) {
                status = http.statusCode.toInt()
                if (location.isNullOrBlank()) location = headerValue(http, "Location")
            }
            if (body.length < 256 * 1024) body += didReceiveData.utf8().orEmpty()
        } catch (t: Throwable) {
            iLog("data cb error: ${t.message}")
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        if (done) return
        try {
            val http = task.response as? NSHTTPURLResponse
            if (http != null && status == 0) {
                status = http.statusCode.toInt()
                if (location.isNullOrBlank()) location = headerValue(http, "Location")
            }
            if (didCompleteWithError != null && status == 0) {
                finish(
                    HttpResult(
                        0,
                        null,
                        "",
                        "请求失败：${didCompleteWithError.localizedDescription}（${didCompleteWithError.code}）",
                    )
                )
                return
            }
            iLog("done $status len=${body.length}")
            finish(HttpResult(status, location, body, null))
        } catch (t: Throwable) {
            iLog("complete cb error: ${t.message}")
            finish(HttpResult(status, location, body, "解析响应失败：${t.message}"))
        }
    }

    /** 只恢复协程一次。 */
    private fun finish(result: HttpResult) {
        if (done) return
        done = true
        val c = cont
        cont = null
        if (c == null || !c.isActive) return
        try {
            c.resume(result)
        } catch (t: Throwable) {
            iLog("resume error: ${t.message}")
        }
    }
}

private fun headerValue(response: NSHTTPURLResponse, name: String): String? =
    response.allHeaderFields.entries
        .firstOrNull { (it.key as? String)?.equals(name, ignoreCase = true) == true }
        ?.value as? String

private fun NSData.utf8(): String? =
    NSString.create(data = this, encoding = NSUTF8StringEncoding) as String?

actual suspend fun platformStartUpdateInstall(pkg: RemotePackage): String {
    iLog("install ${pkg.fileName}")
    return when (val link = resolveDownloadLink(pkg.fileId)) {
        is LinkResult.Ok -> {
            iLog("open browser: ${clipUrl(link.url, 120)}")
            openInBrowser(link.url)
            "已用浏览器打开下载链接，完成后请到「文件 → 下载」里侧载安装。"
        }

        is LinkResult.Failed -> {
            iLog("resolve failed: ${link.message}")
            platformOpenSharePage()
            val trail = crumbTrail()
            if (trail.isBlank()) "${link.message}\n已用浏览器打开分享页。"
            else "${link.message}\n轨迹：$trail\n已用浏览器打开分享页。"
        }
    }
}

actual fun platformOpenSharePage() {
    openInBrowser(SHARE_URL)
}

private fun openInBrowser(url: String) {
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl == null) {
        iLog("invalid browser url: ${clipUrl(url)}")
        return
    }
    // openURL 必须在主线程调：显式派发到主队列，不赌协程恢复线程。
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any>(), null)
    }
}
