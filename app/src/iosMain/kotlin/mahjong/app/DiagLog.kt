package mahjong.app

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fsync
import platform.posix.fwrite

/**
 * iOS 侧启动诊断。
 *
 * 与 Swift 的 CrashLogger 共用同一份 `Documents/crash.log` 和同一组 UserDefaults 键，
 * 这样无论崩溃发生在 Swift 首帧还是 Kotlin 业务代码，下次启动都能看到最后停在哪个阶段。
 * 所有写操作都带 fflush + fsync，尽量保证崩溃前已经落盘。
 */
object Diag {
    private const val STAGE_KEY = "diag.lastStage"
    private const val STAGE_TIME_KEY = "diag.lastStageTime"
    private const val KOTLIN_CRASH_KEY = "diag.pendingKotlinCrash"

    private var installed = false

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    private val timeFormatter by lazy {
        NSDateFormatter().apply { dateFormat = "yyyy-MM-dd HH:mm:ss" }
    }

    fun install() {
        if (installed) return
        installed = true
        mark("kotlin.install")
        installUnhandledHook()
    }

    /** 记一个启动阶段：先写 UserDefaults（下次启动必读），再追加到 crash.log。 */
    fun mark(stage: String) {
        runCatching {
            val time = now()
            defaults.setObject(stage, forKey = STAGE_KEY)
            defaults.setObject(time, forKey = STAGE_TIME_KEY)
            defaults.synchronize()
            append("STAGE $time $stage")
        }
    }

    @OptIn(ExperimentalNativeApi::class)
    private fun installUnhandledHook() {
        // 一个进程只能装一次；已装过就保持原样。
        runCatching {
            setUnhandledExceptionHook { throwable ->
                runCatching {
                    val text = buildString {
                        append("KOTLIN_UNCAUGHT ")
                        append(throwable::class.simpleName ?: "Throwable")
                        append(": ")
                        append(throwable.message ?: "-")
                        append('\n')
                        append(throwable.stackTraceToString())
                    }
                    append(text)
                    defaults.setObject(text, forKey = KOTLIN_CRASH_KEY)
                    defaults.synchronize()
                }
                // 写完后按默认行为终止，保持和没有 hook 时一致（会触发 SIGABRT）。
                terminateWithUnhandledException(throwable)
            }
        }
    }

    private fun now(): String = runCatching { timeFormatter.stringFromDate(NSDate()) }.getOrDefault("-")

    @OptIn(ExperimentalForeignApi::class)
    private fun append(line: String) {
        val path = logPath() ?: return
        val file = fopen(path, "a") ?: return
        try {
            val bytes = (line + "\n").encodeToByteArray()
            if (bytes.isEmpty()) return
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
            }
            fflush(file)
            fsync(fileno(file))
        } finally {
            fclose(file)
        }
    }

    private fun logPath(): String? {
        val dir = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: return null
        return "$dir/crash.log"
    }
}
