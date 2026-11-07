package mahjong.app

/**
 * 调试/截图专用：通过 `adb shell am start ... --es mj_debug claim` 指定要注入的局面。
 * 只在 debug 构建里由 MainActivity 写入，消费一次即清空，不影响正常对局。
 */
object DebugScenario {
    var mode: String? = null

    fun consume(): String? = mode.also { mode = null }
}
