package mahjong.app.update

import mahjong.app.platformAppVersion

/**
 * 更新检查：读取小飞机网盘公开分享目录里的安装包。
 *
 * 分享结构：根 → 「ios」/「安卓」→ 安装包（文件名带版本号，如「竹间麻将-1.0.19.apk」）。
 *
 * 注意：不能直接调 api.feijipan.com 的列表接口 —— 非浏览器客户端只返回空列表
 * （WAF 校验），所以两端都用「隐藏 WebView 渲染分享页 + 读页面文本」的方式取文件列表。
 */
const val SHARE_URL = "https://share.feijipan.com/s/rhzlQN1U"

/** 分享目录里的一个安装包。 */
data class RemotePackage(val version: String, val fileName: String)

/** 读取分享目录的结果：成功时返回该平台文件夹下的原始文本行。 */
sealed interface FeedResult {
    data class Ok(val lines: List<String>) : FeedResult
    data class Failed(val message: String) : FeedResult
}

/** 平台对应的子文件夹名：安卓读「安卓」，iOS 读「ios」。 */
expect fun platformUpdateFolder(): String

/** 平台安装包扩展名：apk / ipa。 */
expect fun platformUpdateExt(): String

/** 用隐藏 WebView 渲染分享页，读出平台文件夹里的文本行。 */
expect suspend fun platformReadShareFolder(): FeedResult

/** 下载并安装：安卓 DownloadManager + 系统安装器；iOS 交系统浏览器下载。 */
expect suspend fun platformStartUpdateInstall(pkg: RemotePackage): String

/** 兜底：用系统浏览器打开分享页。 */
expect fun platformOpenSharePage()

/** 拼成 JS 字符串字面量（文件名里可能有引号、中文、括号）。 */
fun jsQuote(text: String): String = buildString {
    append('"')
    text.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}

/**
 * 按可见文本点击页面元素：先精确匹配，再包含匹配（容忍图标、空白与零宽字符）。
 * 分享页的文件行和文件夹都是可点击的 div，没有 a[href]，只能按文本找。
 */
fun clickByTextJs(text: String): String {
    val want = jsQuote(text)
    return """
        (function(){
          var want = $want;
          var nodes = document.querySelectorAll('div,span,a,p,li');
          for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            if (n.children && n.children.length > 0) continue;
            if ((n.textContent || '').trim() === want) { n.click(); return 'ok'; }
          }
          for (var j = 0; j < nodes.length; j++) {
            var m = nodes[j];
            if (m.children && m.children.length > 0) continue;
            if ((m.textContent || '').trim().indexOf(want) >= 0) { m.click(); return 'ok'; }
          }
          return 'no';
        })()
    """.trimIndent()
}

private val VersionToken = Regex("""(\d+(?:\.\d+){1,3})""")

/**
 * 从分享页文本的一行里解析出安装包。
 *
 * 只要求「扩展名前出现过版本号」，不写死应用名，这样分享页里叫
 * 「竹间麻将-1.0.19.apk」「竹间麻将-v1.0.19.ipa」「zhujian-mahjong-1.0.19-debug.apk」
 * 都能识别，换名也不用改代码。
 */
fun parsePackageLine(line: String, ext: String): RemotePackage? {
    val text = line.trim()
    val marker = ".$ext"
    val index = text.indexOf(marker, ignoreCase = true)
    if (index <= 0) return null
    val prefix = text.substring(0, index)
    val match = VersionToken.findAll(prefix).lastOrNull() ?: return null
    val name = prefix.trim()
    if (name.isEmpty()) return null
    // 文件名里不会出现路径分隔符；出现说明这行不是文件名（比如整段文本粘连）。
    if (name.any { it == '/' || it == '\\' }) return null
    return RemotePackage(version = match.groupValues[1], fileName = "$name$marker")
}

/** 逐段数字比较：a > b 返回 true。 */
fun versionGreater(a: String, b: String): Boolean {
    val pa = a.split('.')
    val pb = b.split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val va = pa.getOrNull(i)?.toIntOrNull() ?: 0
        val vb = pb.getOrNull(i)?.toIntOrNull() ?: 0
        if (va != vb) return va > vb
    }
    return false
}

/** 在一堆文本行里挑出比当前版本新的最高版本。 */
fun pickNewest(lines: List<String>, ext: String, current: String): RemotePackage? =
    lines.mapNotNull { parsePackageLine(it, ext) }
        .filter { versionGreater(it.version, current) }
        .maxWithOrNull { x, y ->
            when {
                versionGreater(x.version, y.version) -> 1
                versionGreater(y.version, x.version) -> -1
                else -> 0
            }
        }

sealed interface UpdateCheckResult {
    val currentVersion: String

    data class NewVersion(override val currentVersion: String, val pkg: RemotePackage) : UpdateCheckResult
    data class UpToDate(override val currentVersion: String) : UpdateCheckResult
    data class NoPackage(override val currentVersion: String) : UpdateCheckResult
    data class Failed(override val currentVersion: String, val message: String) : UpdateCheckResult
}

suspend fun checkForUpdate(): UpdateCheckResult {
    val current = platformAppVersion()
    val ext = platformUpdateExt()
    return when (val feed = platformReadShareFolder()) {
        is FeedResult.Failed -> UpdateCheckResult.Failed(current, feed.message)
        is FeedResult.Ok -> {
            val newest = pickNewest(feed.lines, ext, current)
            when {
                newest != null -> UpdateCheckResult.NewVersion(current, newest)
                feed.lines.none { parsePackageLine(it, ext) != null } -> UpdateCheckResult.NoPackage(current)
                else -> UpdateCheckResult.UpToDate(current)
            }
        }
    }
}
