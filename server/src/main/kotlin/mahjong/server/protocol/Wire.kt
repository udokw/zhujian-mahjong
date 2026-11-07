package mahjong.server.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val PROTOCOL_VERSION = 1

/**
 * 统一消息信封。
 *
 * 客户端 -> 服务端：`cseq` 为连接内单调序号，服务端丢弃重复消息。
 * 服务端 -> 客户端：`sseq` 为房间事件序号，客户端检测跳号后发 `sync_request`。
 */
@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val t: String,
    val sid: String = "",
    val ts: Long = 0,
    val cseq: Long = 0,
    val sseq: Long = 0,
    val room: String = "",
    val seat: Int = -1,
    val p: JsonObject = JsonObject(emptyMap()),
)

class ProtocolException(val code: String, message: String, val sid: String = "") : Exception(message)

object Wire {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun decode(text: String): Envelope = try {
        json.decodeFromString(Envelope.serializer(), text)
    } catch (e: Exception) {
        throw ProtocolException(ErrorCode.BAD_JSON, "无法解析消息: ${e.message}")
    }

    fun encode(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)

    fun encodeEnvelope(
        type: String,
        payload: JsonObject = JsonObject(emptyMap()),
        sid: String = "",
        room: String = "",
        seat: Int = -1,
        sseq: Long = 0,
    ): String = encode(
        Envelope(
            t = type,
            sid = sid,
            ts = System.currentTimeMillis(),
            sseq = sseq,
            room = room,
            seat = seat,
            p = payload,
        ),
    )

    inline fun <reified T> payload(envelope: Envelope): T = try {
        json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), envelope.p)
    } catch (e: Exception) {
        throw ProtocolException(ErrorCode.BAD_JSON, "负载字段缺失或类型错误: ${e.message}", envelope.sid)
    }

    /** 仅用于测试/日志：把负载读成宽松的字符串映射。 */
    fun flatten(envelope: Envelope): Map<String, String> =
        envelope.p.mapValues { (_, v) -> v.toString() }

    fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    fun stringOf(envelope: Envelope, key: String): String? =
        (envelope.p[key] as? JsonObject)?.get(key)?.jsonPrimitive?.content
}
