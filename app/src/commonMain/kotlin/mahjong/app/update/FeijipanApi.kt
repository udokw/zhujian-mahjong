package mahjong.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/**
 * 飞盘（小飞机网盘）公开分享接口。
 *
 * 分享结构：根 → 竹间麻将 → ios / 安卓 → 安装包。
 * 全部走接口，不再用 WebView 渲染分享页：只要带齐 devType/devModel/uuid/extra 与
 * 加密 timestamp，接口对普通 HTTP 客户端同样返回完整列表（2026-11 实测 GET/POST 均为 200）。
 *
 * 失败提示统一带上实际请求地址与 HTTP 结果，便于真机取证。
 */
const val SHARE_ID = "rhzlQN1U"
const val SHARE_URL = "https://share.feijipan.com/s/$SHARE_ID"
const val API_BASE = "https://api.feijipan.com/ws"
const val API_HOST = "api.feijipan.com"
const val SHARE_ORIGIN = "https://share.feijipan.com"
const val APP_FOLDER_NAME = "竹间麻将"

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

/** 接口返回的一个条目：文件夹或文件。 */
data class ShareEntry(
    val name: String,
    val isFolder: Boolean,
    val fileId: String,
    val folderId: String,
) {
    /** 列子目录时要传的 id：文件夹条目一般给 folderId，缺省时退回 fileId。 */
    val childId: String get() = folderId.ifBlank { fileId }
}

/** 一次 HTTP 的结果：不跟随跳转，302 的 Location 单独返回。 */
data class HttpResult(
    val status: Int,
    val location: String?,
    val body: String,
    val error: String?,
)

expect fun feijipanNowMillis(): Long

/** 发一次 GET，不跟随跳转；实现里必须带桌面 UA 与分享页 Referer/Origin。 */
expect suspend fun feijipanHttp(url: String): HttpResult

/** 32 位 hex。接口只要求格式，不要求跨请求一致；同一进程复用同一个更省事。 */
private val sessionUuid: String = buildString {
    val hex = "0123456789abcdef"
    repeat(32) { append(hex[Random.nextInt(16)]) }
}

fun shareListUrl(folderId: String? = null): String = buildString {
    append(API_BASE).append("/share/list?devType=3&devModel=Windows")
    append("&uuid=").append(sessionUuid)
    append("&extra=2")
    append("&timestamp=").append(FeijipanCrypto.encryptHex(feijipanNowMillis().toString()))
    append("&shareId=").append(SHARE_ID)
    append("&offset=1&limit=100")
    if (!folderId.isNullOrBlank()) append("&folderId=").append(folderId)
}

fun shareRedirectUrl(fileId: String): String {
    val ts = feijipanNowMillis().toString()
    return buildString {
        append(API_BASE).append("/file/redirect?downloadId=")
        append(FeijipanCrypto.encryptHex("$fileId|"))
        append("&enable=1&devType=3&uuid=").append(sessionUuid)
        append("&timestamp=").append(FeijipanCrypto.encryptHex(ts))
        append("&auth=").append(FeijipanCrypto.encryptHex("$fileId|$ts"))
        append("&shareId=").append(SHARE_ID)
    }
}

/** 读一个目录的结果。 */
sealed interface FeedResult {
    data class Ok(val entries: List<ShareEntry>) : FeedResult
    data class Failed(val message: String) : FeedResult
}

/** 提示里放完整地址太长，保留主机与参数开头即可判断问题出在哪一步。 */
fun clipUrl(url: String, max: Int = 180): String =
    if (url.length <= max) url else url.take(max) + "…"

private suspend fun listOnce(folderId: String?): FeedResult {
    val url = shareListUrl(folderId)
    val result = feijipanHttp(url)
    if (result.error != null) {
        return FeedResult.Failed("读取分享目录失败：${result.error}\n地址：${clipUrl(url)}")
    }
    if (result.status !in 200..299) {
        return FeedResult.Failed("读取分享目录失败：HTTP ${result.status}\n地址：${clipUrl(url)}")
    }
    return parseShareList(result.body)
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun parseShareList(body: String): FeedResult {
    if (body.isBlank()) return FeedResult.Failed("接口返回空响应")
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: return FeedResult.Failed("接口返回无法解析：${body.take(120)}")

    val code = root.text("code")?.toIntOrNull() ?: -1
    if (code != 200) {
        return FeedResult.Failed("接口返回 code=$code ${root.text("msg").orEmpty()}".trim())
    }

    val list = root["list"] as? JsonArray ?: JsonArray(emptyList())
    val entries = list.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = obj.text("fileName") ?: obj.text("name") ?: return@mapNotNull null
        ShareEntry(
            name = name,
            isFolder = obj.text("fileType") == "2",
            fileId = obj.text("fileId").orEmpty(),
            folderId = obj.text("folderId").orEmpty(),
        )
    }
    return FeedResult.Ok(entries)
}

/** 逐层进入「竹间麻将 / 平台文件夹」，返回该目录下的全部条目。 */
suspend fun readPlatformFolder(): FeedResult {
    val appName = APP_FOLDER_NAME
    val platform = platformUpdateFolder()

    fun names(list: List<ShareEntry>) = list.joinToString("、") { it.name }.take(160)

    val root = listOnce(null)
    val rootEntries = when (root) {
        is FeedResult.Ok -> root.entries
        is FeedResult.Failed -> return root
    }
    val appFolder = rootEntries.firstOrNull { it.isFolder && it.name.contains(appName, ignoreCase = true) }
        ?: return FeedResult.Failed(
            "分享根目录里没找到「$appName」文件夹（现有：${names(rootEntries)}）"
        )

    val appEntries = when (val appList = listOnce(appFolder.childId)) {
        is FeedResult.Ok -> appList.entries
        is FeedResult.Failed -> return appList
    }
    val platformFolder = appEntries.firstOrNull { it.isFolder && it.name.trim().equals(platform, true) }
        ?: appEntries.firstOrNull { it.isFolder && it.name.contains(platform, true) }
        ?: return FeedResult.Failed(
            "「$appName」里没找到「$platform」文件夹（现有：${names(appEntries)}）"
        )

    return listOnce(platformFolder.childId)
}

/** 取直链的结果。 */
sealed interface LinkResult {
    data class Ok(val url: String) : LinkResult
    data class Failed(val message: String) : LinkResult
}

private val UrlInText = Regex("""https?://[^\s"'<>\\]+""")

/** 接口偶尔不用 302 而是把地址包在 JSON 里，兜底从响应体里挑出来。 */
private fun extractDownloadUrl(body: String): String? {
    if (body.isBlank()) return null
    val normalized = body.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
    return UrlInText.findAll(normalized)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
        .firstOrNull { !it.contains(API_HOST) && !it.contains("share.feijipan.com") }
}

/**
 * 取安装包直链：接口 302 到 CDN，只读 Location，不把安装包读进内存。
 * 直链带签名会过期，所以每次安装都现取，绝不缓存。
 */
suspend fun resolveDownloadLink(fileId: String): LinkResult {
    if (fileId.isBlank()) return LinkResult.Failed("安装包缺少 fileId，请重新检测更新")
    var current = shareRedirectUrl(fileId)
    var last = "未知错误"
    repeat(3) {
        val result = feijipanHttp(current)
        if (result.error != null) {
            return LinkResult.Failed("取下载直链失败：${result.error}\n地址：${clipUrl(current)}")
        }
        if (result.status in 300..399) {
            val location = result.location
            if (location.isNullOrBlank()) {
                return LinkResult.Failed("接口返回 HTTP ${result.status} 但没有 Location 头")
            }
            // 还在接口域就继续跟一跳，落到 CDN 才收手。
            if (!location.contains(API_HOST)) return LinkResult.Ok(location)
            if (location == current) return LinkResult.Failed("接口反复跳回自己：${clipUrl(current)}")
            current = location
            return@repeat
        }
        extractDownloadUrl(result.body)?.let { return LinkResult.Ok(it) }
        last = "接口返回 HTTP ${result.status}：${result.body.take(160).replace('\n', ' ')}"
        return LinkResult.Failed(last)
    }
    return LinkResult.Failed("接口跳转次数过多，没能拿到 CDN 直链：$last")
}
