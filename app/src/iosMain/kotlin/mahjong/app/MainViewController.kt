package mahjong.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.delay
import platform.Foundation.NSBundle
import platform.Foundation.NSNumber
import platform.UIKit.UIViewController

/**
 * 首帧探针开关，来自 Info.plist 的 MJBootProbe（缺省开启）。
 *
 * 目的：先用最朴素的 Compose 内容渲染一帧，再切到真实 App，把
 * 「Compose/Skiko 渲染栈本身」和「业务首屏」两类崩溃区分开。
 */
private val bootProbeEnabled: Boolean by lazy {
    val value = NSBundle.mainBundle.objectForInfoDictionaryKey("MJBootProbe")
    (value as? NSNumber)?.boolValue ?: true
}

/** 极简首帧：不使用主题、不读资源、不做动画，只验证渲染栈能不能出图。 */
@Composable
private fun BootProbeScreen(onDone: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF22594A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "boot", color = Color.White, fontSize = 40.sp)
    }

    LaunchedEffect(Unit) {
        Diag.mark("compose.probe.composed")
        withFrameNanos { }
        Diag.mark("compose.probe.first-frame")
        // 让探针画面在屏幕上停一会儿，肉眼能确认它确实渲染出来了。
        delay(900)
        Diag.mark("compose.probe.done")
        onDone()
    }
}

@Composable
private fun RootContent() {
    var probing by remember { mutableStateOf(bootProbeEnabled) }
    if (probing) {
        BootProbeScreen { probing = false }
    } else {
        // 阶段顺序：探针完成 → 真实 App 开始组合 → 真实 App 首帧完成（ready）。
        // 崩在不同阶段，下次启动的诊断页就能指出是渲染栈还是业务首屏。
        LaunchedEffect(Unit) {
            Diag.mark("compose.real-app.enter")
            withFrameNanos { }
            Diag.mark("ready")
        }
        App()
    }
}

@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController {
    Diag.install()
    Diag.mark("kotlin.mainvc")
    return ComposeUIViewController { RootContent() }
}
