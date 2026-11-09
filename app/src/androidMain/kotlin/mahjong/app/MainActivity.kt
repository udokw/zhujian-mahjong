package mahjong.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformContext.init(this)
        // 截图/联调用的局面注入：--es mj_debug claim|multi|rob
        DebugScenario.mode = intent?.getStringExtra("mj_debug")
        // 亮屏由设置项统一控制（App() 里的 platformSetKeepScreenOn），这里不再无条件加 FLAG。
        enableEdgeToEdge()
        setContent { App() }
    }
}
