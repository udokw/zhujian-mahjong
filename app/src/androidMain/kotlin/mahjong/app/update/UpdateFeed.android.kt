package mahjong.app.update

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mahjong.app.PlatformContext
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * 安卓侧更新通道：隐藏 WebView 渲染分享页 + DownloadManager 下载 + 系统安装器。
 *
 * 为什么不用接口：api.feijipan.com 的分享列表对非浏览器客户端直接返回空列表，
 * 只有真正把分享页的 Vue 渲染出来才能读到文件名（见 feijipan-update 技能记录）。
 *
 * WebView 必须给足尺寸（MATCH_PARENT）再设为 INVISIBLE：页面按宽度做响应式布局，
 * 太小会把文件行整行隐藏；INVISIBLE 不绘制、不吃触摸，不会挡住牌桌。
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

private val mainHandler = Handler(Looper.getMainLooper())

private var web: WebView? = null
private var hostActivity: Activity? = null
private var downloadLinkSink: ((String?) -> Unit)? = null
private var downloadId = -1L
private var pendingFileName = ""
private var receiverRegistered = false

actual fun platformUpdateFolder(): String = "安卓"

actual fun platformUpdateExt(): String = "apk"

actual suspend fun platformReadShareFolder(): FeedResult = withContext(Dispatchers.Main) {
    val activity = PlatformContext.activity
        ?: return@withContext FeedResult.Failed("应用界面未就绪，请稍后重试。")
    openPlatformFolder(ensureWebView(activity))
}

private const val TAG = "UpdateFeed"

/** 打开分享页并进入平台文件夹，返回目录里的文本行。 */
private suspend fun openPlatformFolder(view: WebView): FeedResult {
    val folder = platformUpdateFolder()

    // loadUrl 是异步的：上一次检查可能把 WebView 留在某个目录页，这时读到的文本和
    // 点击都会落在旧文档上（点击返回 ok 但页面没换）。先切空白页把旧文档卸掉。
    view.loadUrl("about:blank")
    awaitText(view, 3_000) { it.isBlank() }
    view.loadUrl(SHARE_URL)
    Log.d(TAG, "loadUrl $SHARE_URL")

    // 根目录的「文件名/夹」表头先于文件行渲染，必须等文件夹行真正出现再点，否则点击落空。
    val root = awaitText(view, 25_000) { t -> t.contains("文件名") && t.contains(folder) }
        ?: return FeedResult.Failed("打开分享页超时，请检查网络后重试。")
    Log.d(TAG, "root ready: ${oneLine(root)}")

    val clicked = evalScriptSuspending(view, clickByTextJs(folder))
    Log.d(TAG, "click($folder) => $clicked")
    if (clicked != "ok") {
        return FeedResult.Failed("分享页里没找到「$folder」文件夹，请确认分享结构。")
    }
    val text = awaitFolderListing(view, root)
        ?: return FeedResult.Failed("进入「$folder」文件夹超时，请稍后重试。")
    Log.d(TAG, "folder ready: ${oneLine(text)}")

    return FeedResult.Ok(text.split('\n'))
}

/**
 * 等目录页出结果。
 *
 * 进目录后页面还要再请求一次文件列表，存在「骨架屏」中间态；目录也可能是空的。
 * 因此：出现安装包行立刻返回；没有安装包行时，等文本稳定约 1.8s 就按空目录返回，
 * 交给上层提示「分享目录里还没有安装包」，而不是一直干等到超时。
 */
private suspend fun awaitFolderListing(
    view: WebView,
    root: String,
    timeoutMs: Long = 20_000,
): String? {
    val started = System.currentTimeMillis()
    val deadline = started + timeoutMs
    var last = ""
    var stable = 0
    while (System.currentTimeMillis() < deadline) {
        val text = evalText(view)
        if (text.contains(".apk", ignoreCase = true) || text.contains(".ipa", ignoreCase = true)) {
            return text
        }
        if (text.isNotBlank() && text != root && text == last) {
            stable++
            if (stable >= 3) {
                Log.d(TAG, "folder listing settled empty: ${oneLine(text)}")
                return text
            }
        } else {
            stable = 0
        }
        last = text
        delay(600)
    }
    Log.d(TAG, "folder listing timeout, last text: ${oneLine(evalText(view))}")
    return null
}

private fun oneLine(text: String): String = text.replace('\n', '|').take(220)

actual suspend fun platformStartUpdateInstall(pkg: RemotePackage): String = withContext(Dispatchers.Main) {
    val activity = PlatformContext.activity
        ?: return@withContext "应用界面未就绪，请稍后重试。"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !activity.packageManager.canRequestPackageInstalls()
    ) {
        openInstallPermission(activity)
        return@withContext "请先允许本应用「安装未知应用」，再点一次「下载更新」继续。"
    }

    val view = ensureWebView(activity)
    // 「下载更新」可能距上次检测已经过了一段时间，WebView 未必还停在包列表页：
    // 当前页面看不到目标文件就重新进一次文件夹，避免盲点导致取不到直链。
    if (!evalText(view).contains(pkg.fileName)) {
        val listing = openPlatformFolder(view)
        if (listing is FeedResult.Failed) {
            platformOpenSharePage()
            return@withContext "${listing.message}已用浏览器打开分享页。"
        }
    }
    val apiUrl = resolveDownloadLink(view, pkg.fileName)
    if (apiUrl.isNullOrBlank()) {
        Log.w(TAG, "capture failed: webview gave no url for ${pkg.fileName}")
        platformOpenSharePage()
        return@withContext "没能拿到下载链接（页面没有触发下载），已用浏览器打开分享页。"
    }
    Log.d(TAG, "captured api url: $apiUrl")

    // 接口地址只留在 App 内跟 302：直接把接口地址交给 DownloadManager，
    // 它会缺 Referer / 桌面 UA / Cookie，拿到的是「参数有误」的 JSON。
    when (val resolved = resolveFinalUrl(apiUrl)) {
        is ResolveResult.Ok -> {
            if (resolved.url.contains(API_HOST)) {
                Log.w(TAG, "resolved still api url, abort: ${resolved.url}")
                platformOpenSharePage()
                return@withContext "接口没有返回跳转地址，已用浏览器打开分享页。\n接口地址：${clip(apiUrl)}"
            }
            Log.d(TAG, "download url: ${resolved.url}")
            enqueueDownload(activity, resolved.url, pkg.fileName)
        }

        is ResolveResult.Failed -> {
            Log.w(TAG, "resolve failed: ${resolved.reason} | api=$apiUrl")
            platformOpenSharePage()
            "取下载直链失败：${resolved.reason}\n接口地址：${clip(apiUrl)}\n已用浏览器打开分享页。"
        }
    }
}

/** 取链接口域名：只是 302 中转，不能当下载地址。 */
private const val API_HOST = "api.feijipan.com"

private sealed interface ResolveResult {
    data class Ok(val url: String) : ResolveResult
    data class Failed(val reason: String) : ResolveResult
}

private fun clip(url: String, max: Int = 220): String =
    if (url.length <= max) url else url.take(max) + "…"

/**
 * 在 App 内把接口地址跟完 302，取出 CDN 直链。
 *
 * 网盘接口可能连跳两三层，这里最多跟 3 跳；一旦落到非接口域名就收手，
 * 剩下的跳转 DownloadManager 自己会跟。非 3xx 时看响应类型——JSON/HTML
 * 说明被风控挡了，直接把状态码与响应片段报出来，绝不把这种地址交给下载器。
 */
private suspend fun resolveFinalUrl(apiUrl: String): ResolveResult {
    var current = apiUrl
    var last: ResolveResult = ResolveResult.Failed("未知错误")
    repeat(3) {
        val result = withContext(Dispatchers.IO) { requestOnce(current) }
        last = result
        if (result !is ResolveResult.Ok) return result
        if (!result.url.contains(API_HOST)) return result
        if (result.url == current) return ResolveResult.Failed("接口反复跳回自己：${clip(current)}")
        current = result.url
    }
    return last
}

private fun requestOnce(apiUrl: String): ResolveResult {
    var opened: HttpURLConnection? = null
    return try {
        val http = URL(apiUrl).openConnection() as HttpURLConnection
        opened = http
        http.instanceFollowRedirects = false
        http.requestMethod = "GET"
        http.connectTimeout = 15_000
        http.readTimeout = 15_000
        http.setRequestProperty("User-Agent", DESKTOP_UA)
        http.setRequestProperty("Referer", SHARE_URL)
        http.setRequestProperty("Origin", "https://share.feijipan.com")
        http.setRequestProperty("Accept", "application/json, text/plain, */*")
        CookieManager.getInstance().getCookie(apiUrl)?.let { cookie ->
            Log.d(TAG, "attach cookie: ${oneLine(cookie).take(160)}")
            http.setRequestProperty("Cookie", cookie)
        }

        val code = http.responseCode
        val contentType = http.contentType.orEmpty()
        Log.d(TAG, "resolve http=$code type=$contentType")
        if (code in 301..308) {
            val location = http.getHeaderField("Location")
            if (location.isNullOrBlank()) {
                return ResolveResult.Failed("HTTP $code 但没有 Location 头")
            }
            val absolute = runCatching { URL(URL(apiUrl), location).toString() }.getOrDefault(location)
            Log.d(TAG, "location: $absolute")
            return ResolveResult.Ok(absolute)
        }

        val textLike = contentType.contains("json", true) ||
            contentType.contains("text", true) ||
            contentType.contains("html", true)
        if (code in 200..299 && !textLike) {
            // 接口直接把文件流吐回来：当前地址本身就是直链。
            return ResolveResult.Ok(apiUrl)
        }

        val stream = if (code >= 400) http.errorStream else http.inputStream
        val snippet = runCatching {
            stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(240)
                val n = reader.read(buffer)
                if (n > 0) String(buffer, 0, n) else ""
            }.orEmpty()
        }.getOrDefault("")
        ResolveResult.Failed("接口返回 $code $contentType：${snippet.replace('\n', ' ')}")
    } catch (t: Throwable) {
        ResolveResult.Failed("请求失败：${t.message ?: t::class.simpleName}")
    } finally {
        opened?.disconnect()
    }
}

actual fun platformOpenSharePage() {
    val context = PlatformContext.app
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHARE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

// ---------------------------------------------------------------- WebView

@SuppressLint("SetJavaScriptEnabled")
private fun ensureWebView(activity: Activity): WebView {
    val existing = web
    if (existing != null && hostActivity === activity) return existing

    existing?.let { old ->
        (old.parent as? ViewGroup)?.removeView(old)
        old.destroy()
    }

    val view = WebView(activity)
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = DESKTOP_UA
        setSupportZoom(false)
    }
    view.setBackgroundColor(Color.TRANSPARENT)
    view.isVerticalScrollBarEnabled = false
    view.isHorizontalScrollBarEnabled = false
    view.webViewClient = object : WebViewClient() {
        // 不重写 shouldOverrideUrlLoading：目录跳转是前端路由，WebView 内部消化。
    }
    view.setDownloadListener(
        DownloadListener { url, _, _, _, _ ->
            downloadLinkSink?.invoke(url)
        }
    )

    val container = activity.findViewById<ViewGroup>(android.R.id.content)
    container?.addView(
        view,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    view.visibility = View.INVISIBLE
    web = view
    hostActivity = activity
    return view
}

/** 轮询页面文本，直到满足条件或超时。 */
private suspend fun awaitText(
    view: WebView,
    timeoutMs: Long,
    predicate: (String) -> Boolean,
): String? {
    val started = System.currentTimeMillis()
    val deadline = started + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val text = evalText(view)
        val hit = predicate(text)
        Log.d(TAG, "poll +${System.currentTimeMillis() - started}ms len=${text.length} hit=$hit: ${oneLine(text)}")
        if (hit) return text
        delay(600)
    }
    return null
}

private suspend fun evalText(view: WebView): String =
    evalScriptSuspending(view, "document.body ? document.body.innerText : ''")

private suspend fun evalScriptSuspending(view: WebView, script: String): String =
    suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { value ->
            if (cont.isActive) cont.resume(decodeJsString(value))
        }
    }

private fun evalScript(view: WebView, script: String) {
    view.evaluateJavascript(script, null)
}

private fun evalScript(view: WebView, script: String, onResult: (String) -> Unit) {
    view.evaluateJavascript(script) { value -> onResult(decodeJsString(value)) }
}

/** evaluateJavascript 返回的是 JSON 字面量，需要自己解引号与转义。 */
private fun decodeJsString(value: String?): String {
    if (value.isNullOrEmpty() || value == "null") return ""
    return runCatching {
        val parsed = JSONTokener(value).nextValue()
        when (parsed) {
            is String -> parsed
            null -> ""
            else -> parsed.toString()
        }
    }.getOrDefault("")
}

/**
 * 选中文件行 → 等下载按钮出现 → 点 .down-btn。
 * 直链带签名会过期，所以每次都现取，绝不缓存。
 */
private suspend fun resolveDownloadLink(view: WebView, fileName: String): String? =
    withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine<String?> { cont ->
            downloadLinkSink = { url ->
                if (cont.isActive && !url.isNullOrBlank()) {
                    downloadLinkSink = null
                    cont.resume(url)
                }
            }
            cont.invokeOnCancellation { downloadLinkSink = null }
            evalScript(view, clickByTextJs(fileName)) { result ->
                if (result != "ok") {
                    downloadLinkSink = null
                    if (cont.isActive) cont.resume(null)
                    return@evalScript
                }
                mainHandler.postDelayed({
                    evalScript(
                        view,
                        "var b=document.querySelector('.down-btn'); if(!b){'no'} else { b.click(); 'ok' }",
                    ) { clicked ->
                        if (clicked != "ok") {
                            downloadLinkSink = null
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }, 1_200)
            }
        }
    }

// ---------------------------------------------------------------- 下载与安装

private val downloadReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id != downloadId) return
        installDownloaded(context)
    }
}

private fun registerReceiver(context: Context) {
    if (receiverRegistered) return
    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, filter)
        }
        receiverRegistered = true
    }
}

private fun enqueueDownload(context: Context, url: String, fileName: String): String {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    if (manager == null) {
        platformOpenSharePage()
        return "系统下载服务不可用，已用浏览器打开分享页。"
    }
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(fileName)
        .setDescription("竹间麻将更新包")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10 起 DownloadManager 可以直接写公共下载目录，不需要存储权限。
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
    } else {
        // 9 及以下写公共目录要 WRITE_EXTERNAL_STORAGE；退到应用专属下载目录，
        // 文件名保持不变，下载完成后的通知里同样能看到。
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
    }
    pendingFileName = fileName
    downloadId = manager.enqueue(request)
    registerReceiver(context)
    return "正在下载 $fileName，完成后会调起系统安装器。"
}

private fun installDownloaded(context: Context) {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
    val query = DownloadManager.Query().setFilterById(downloadId)
    runCatching {
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return@use
            val file = stageDownloadedApk(context, manager, pendingFileName) ?: return@use
            val problem = apkProblem(context, file)
            if (problem != null) {
                Log.w(TAG, "invalid apk: $problem (${file.length()}B)")
                Toast.makeText(context, "安装包校验失败：$problem，请重新下载", Toast.LENGTH_LONG).show()
                return@use
            }
            installApk(context, file)
        }
    }
}

/**
 * 下载好的包先复制到应用私有目录再交安装器。
 *
 * 公共下载目录在分区存储下只能通过 content:// 访问，系统安装器又拿不到我们的读授权，
 * 所以用 DownloadManager.openDownloadedFile 复制一份到 cache/updates 再以 content:// 交出去。
 */
private fun stageDownloadedApk(context: Context, manager: DownloadManager, fileName: String): File? {
    val target = File(stagingDir(context), fileName)
    if (target.isFile && target.length() > 0L) return target
    runCatching {
        // AutoCloseInputStream 负责关闭 ParcelFileDescriptor，避免重复 close。
        ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(downloadId)).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }
    if (target.isFile && target.length() > 0L) return target
    // 兜底：9 及以下直接落在应用专属下载目录，不需要复制。
    val fallback = File(downloadDir(context), fileName)
    return if (fallback.isFile && fallback.length() > 0L) fallback else null
}

/** 安装前校验：挡掉错误页、残缺文件与包名不符的包；返回 null 表示可以安装。 */
private fun apkProblem(context: Context, file: File): String? {
    if (file.length() < 1_000_000L) return "文件只有 ${file.length() / 1024}KB"
    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        ?: return "无法解析安装包"
    if (info.packageName != context.packageName) return "包名不符（${info.packageName}）"
    return null
}

private fun installApk(context: Context, file: java.io.File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        openInstallPermission(context)
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ApkProvider.uriFor(context, file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }.onFailure {
        platformOpenSharePage()
    }
}

private fun openInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

internal fun downloadDir(context: Context): File =
    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

/** 安装前的暂存目录：交系统安装器时只认应用自己的 content://。 */
internal fun stagingDir(context: Context): File =
    File(context.cacheDir, "updates").apply { mkdirs() }
