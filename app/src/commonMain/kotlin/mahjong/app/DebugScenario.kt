package mahjong.app

/**
 * 调试/截图专用：通过 `adb shell am start ... --es mj_debug claim` 指定要注入的局面。
 *
 * 仅在「debug 构建 + 显式 -PmjDebugScenario=true」时生效：写入与消费都会先检查
 * [platformDebugInjectionEnabled]，随包发布的正式 APK 即使传入 `mj_debug` extra
 * 也不会保存或返回任何局面。
 */
object DebugScenario {
    private var pending: String? = null

    /** 正式包恒为 false；调试注入的所有入口都以它为准。 */
    val enabled: Boolean get() = platformDebugInjectionEnabled()

    /** 只在允许注入的构建里记住请求；消费一次即清空。 */
    fun offer(raw: String?) {
        pending = if (enabled) raw?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    fun consume(): String? = if (!enabled) null else pending.also { pending = null }
}
