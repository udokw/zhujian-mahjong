package mahjong.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings.Secure
import android.view.WindowManager
import java.util.UUID

/** Android 侧需要一个 Application Context，由 MainActivity 注入。 */
object PlatformContext {
    lateinit var app: Context
        private set

    /** 当前前台 Activity，用于窗口级亮屏开关；进程重建后由 MainActivity 重新注入。 */
    var activity: Activity? = null
        private set

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        if (context is Activity) activity = context
    }

    fun setKeepScreenOn(on: Boolean) {
        val act = activity ?: return
        act.runOnUiThread {
            if (on) {
                act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private class AndroidStore(private val context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("mahjong_app", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

actual fun keyValueStore(): KeyValueStore = AndroidStore(PlatformContext.app)

actual fun platformDeviceId(): String {
    val store = keyValueStore()
    store.get("device_id")?.let { return it }
    val id = runCatching {
        Secure.getString(PlatformContext.app.contentResolver, Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(12)
    store.put("device_id", id)
    return id
}

actual fun platformAppVersion(): String = runCatching {
    PlatformContext.app.packageManager
        .getPackageInfo(PlatformContext.app.packageName, 0).versionName
}.getOrNull() ?: "1.0.0"

actual fun platformVibrate(strength: Int) {
    val context = PlatformContext.app
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return
    if (!vibrator.hasVibrator()) return
    val ms = when (strength) {
        0 -> 12L
        1 -> 22L
        else -> 40L
    }
    val amp = when (strength) {
        0 -> 70
        1 -> 140
        else -> 220
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(ms, amp))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(ms)
    }
}

private var tone: ToneGenerator? = null

actual fun platformBeep(volume: Float) {
    if (volume <= 0f) return
    val t = tone ?: runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, (volume.coerceIn(0f, 1f) * 100).toInt())
    }.getOrNull()?.also { tone = it } ?: return
    runCatching {
        t.startTone(ToneGenerator.TONE_PROP_BEEP, 45)
    }
}

actual fun platformDefaultServerUrl(): String = "ws://10.0.2.2:8080/ws"

actual fun platformSetKeepScreenOn(keepOn: Boolean) = PlatformContext.setKeepScreenOn(keepOn)

actual fun platformFormatTime(epochMs: Long): String = runCatching {
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
}.getOrDefault("-")
