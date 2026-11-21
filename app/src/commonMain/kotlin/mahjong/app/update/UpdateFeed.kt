package mahjong.app.update

import mahjong.app.platformAppVersion

/**
 * 更新检查：直连飞盘接口读「竹间麻将 / 平台文件夹」里的安装包，挑出比当前新的最高版本。
 *
 * 文件名带版本号即可识别（「竹间麻将-v1.0.21.ipa」「zhujian-mahjong-1.0.21-debug.apk」都行），
 * 不写死应用名，换名不用改代码。
 */

/** 分享目录里的一个安装包。 */
data class RemotePackage(
    val version: String,
    val fileName: String,
    val fileId: String,
)

/** 平台对应的子文件夹名：安卓读「安卓」，iOS 读「ios」。 */
expect fun platformUpdateFolder(): String

/** 平台安装包扩展名：apk / ipa。 */
expect fun platformUpdateExt(): String

/** 下载并安装：安卓 DownloadManager + 系统安装器；iOS 交系统浏览器下载。 */
expect suspend fun platformStartUpdateInstall(pkg: RemotePackage): String

/** 兜底：用系统浏览器打开分享页。 */
expect fun platformOpenSharePage()

private val VersionToken = Regex("""(\d+(?:\.\d+){1,3})""")

/**
 * 从目录条目里解析安装包：扩展名前出现过版本号即可。
 * 文件夹、其它扩展名、以及没有版本号的文件一律跳过。
 */
fun parsePackage(entry: ShareEntry, ext: String): RemotePackage? {
    if (entry.isFolder || entry.fileId.isBlank()) return null
    val name = entry.name.trim()
    val marker = ".$ext"
    val index = name.indexOf(marker, ignoreCase = true)
    if (index <= 0) return null
    val prefix = name.substring(0, index)
    val match = VersionToken.findAll(prefix).lastOrNull() ?: return null
    return RemotePackage(
        version = match.groupValues[1],
        fileName = name,
        fileId = entry.fileId,
    )
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

/** 在目录条目里挑出比当前版本新的最高版本。 */
fun pickNewest(entries: List<ShareEntry>, ext: String, current: String): RemotePackage? =
    entries.mapNotNull { parsePackage(it, ext) }
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
    return when (val feed = readPlatformFolder()) {
        is FeedResult.Failed -> UpdateCheckResult.Failed(current, feed.message)
        is FeedResult.Ok -> {
            val newest = pickNewest(feed.entries, ext, current)
            when {
                newest != null -> UpdateCheckResult.NewVersion(current, newest)
                feed.entries.none { parsePackage(it, ext) != null } -> UpdateCheckResult.NoPackage(current)
                else -> UpdateCheckResult.UpToDate(current)
            }
        }
    }
}
