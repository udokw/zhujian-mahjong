package mahjong.app

import platform.Foundation.NSUserDefaults
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIApplication
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UIDevice

private class IosStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun put(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}

actual fun keyValueStore(): KeyValueStore = IosStore()

actual fun platformDeviceId(): String {
    val store = keyValueStore()
    store.get("device_id")?.let { return it }
    val id = UIDevice.currentDevice.identifierForVendor?.UUIDString ?: "ios-local"
    store.put("device_id", id)
    return id
}

actual fun platformAppVersion(): String =
    (platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?: "1.0.0"

actual fun platformVibrate(strength: Int) {
    val style = when (strength) {
        0 -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
        1 -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
        else -> UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy
    }
    UIImpactFeedbackGenerator(style).impactOccurred()
}

actual fun platformBeep(volume: Float) {
    // iOS 端使用系统提示音需要 AudioToolbox；首版不引入音频资源，保留开关位。
}

actual fun platformDefaultServerUrl(): String = "ws://127.0.0.1:8080/ws"

actual fun platformSetKeepScreenOn(keepOn: Boolean) {
    UIApplication.sharedApplication.isIdleTimerDisabled = keepOn
}

actual fun platformFormatTime(epochMs: Long): String = runCatching {
    val formatter = platform.Foundation.NSDateFormatter().apply { dateFormat = "MM-dd HH:mm" }
    formatter.stringFromDate(platform.Foundation.NSDate(timeIntervalSince1970 = epochMs / 1000.0))
}.getOrDefault("-")
