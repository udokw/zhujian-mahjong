package mahjong.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mahjong.app.PlatformContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 安卓侧更新通道：直连接口取直链 + DownloadManager 下载 + 系统安装器。
 *
 * 不再用 WebView：接口带齐参数就能直接返回目录与 302 直链，省掉隐藏 WebView
 * 的渲染等待与异步竞态。
 */
private const val TAG = "UpdateFeed"

actual fun platformUpdateFolder(): String = "安卓"

actual fun platformUpdateExt(): String = "apk"

actual fun feijipanNowMillis(): Long = System.currentTimeMillis()

actual suspend fun feijipanHttp(url: String): HttpResult = withContext(Dispatchers.IO) {
    var opened: HttpURLConnection? = null
    try {
        val http = URL(url).openConnection() as HttpURLConnection
        opened = http
        http.instanceFollowRedirects = false
        http.requestMethod = "GET"
        http.connectTimeout = 12_000
        http.readTimeout = 15_000
        http.setRequestProperty("User-Agent", DESKTOP_UA)
        http.setRequestProperty("Referer", "$SHARE_ORIGIN/")
        http.setRequestProperty("Origin", SHARE_ORIGIN)
        http.setRequestProperty("Accept", "application/json, text/plain, */*")
        http.setRequestProperty("Content-Type", "application/json")
        // 接口要求声明支持压缩：不带 gzip/deflate 时返回 code=200 但 list 为空（2026-11-21 实测）。
        // HttpURLConnection 手动设了 Accept-Encoding 就不会自动解压，下面要自己解 gzip。
        http.setRequestProperty("Accept-Encoding", "gzip")

        val code = http.responseCode
        val location = http.getHeaderField("Location")
        val encoding = http.getHeaderField("Content-Encoding").orEmpty().lowercase()
        val body = runCatching {
            val stream = if (code >= 400) http.errorStream else http.inputStream
            when {
                stream == null -> ""
                encoding.contains("gzip") -> GZIPInputStream(stream).bufferedReader().use { it.readText() }
                else -> stream.bufferedReader().use { it.readText() }
            }
        }.getOrDefault("")
        Log.d(TAG, "http=$code len=${body.length} url=${clipUrl(url, 120)}")
        HttpResult(
            status = code,
            location = location,
            body = body.take(256 * 1024),
            error = null,
        )
    } catch (t: Throwable) {
        Log.w(TAG, "http failed: ${t.message} url=${clipUrl(url, 120)}")
        HttpResult(0, null, "", t.message ?: t::class.simpleName ?: "网络请求失败")
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

actual suspend fun platformStartUpdateInstall(pkg: RemotePackage): String = withContext(Dispatchers.Main) {
    val activity = PlatformContext.activity
        ?: return@withContext "应用界面未就绪，请稍后重试。"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !activity.packageManager.canRequestPackageInstalls()
    ) {
        openInstallPermission(activity)
        return@withContext "请先允许本应用「安装未知应用」，再点一次「下载更新」继续。"
    }

    when (val link = resolveDownloadLink(pkg.fileId)) {
        is LinkResult.Ok -> {
            Log.d(TAG, "download url: ${clipUrl(link.url, 160)}")
            enqueueDownload(activity, link.url, pkg.fileName)
        }

        is LinkResult.Failed -> {
            Log.w(TAG, "resolve failed: ${link.message}")
            platformOpenSharePage()
            "${link.message}\n已用浏览器打开分享页。"
        }
    }
}

// ---------------------------------------------------------------- 下载与安装

private var downloadId = -1L
private var pendingFileName = ""
private var receiverRegistered = false

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

private fun installApk(context: Context, file: File) {
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
