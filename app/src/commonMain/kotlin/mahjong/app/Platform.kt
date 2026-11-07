package mahjong.app

/** 跨平台最小的本地能力集合；具体实现放在 androidMain / iosMain。 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

expect fun keyValueStore(): KeyValueStore

expect fun platformDeviceId(): String

expect fun platformAppVersion(): String

/** 0 = 轻（选牌）、1 = 中（碰杠）、2 = 重（胡）。 */
expect fun platformVibrate(strength: Int)

expect fun platformBeep(volume: Float)

expect fun platformDefaultServerUrl(): String
