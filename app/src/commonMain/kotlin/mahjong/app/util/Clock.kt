package mahjong.app.util

import kotlin.time.TimeSource

/** 跨平台单调时钟，用于倒计时与动画计时。 */
object Clock {
    private val origin = TimeSource.Monotonic.markNow()

    fun now(): Long = origin.elapsedNow().inWholeMilliseconds
}
