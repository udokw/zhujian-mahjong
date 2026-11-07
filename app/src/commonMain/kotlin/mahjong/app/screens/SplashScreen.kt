package mahjong.app.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.ui.MjTile
import mahjong.app.ui.MjText
import mahjong.app.util.drawTableFelt

@Composable
fun SplashScreen() {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawTableFelt(colors)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp)),
                )
                MjTile(tile = 9, width = 48.dp, height = 64.dp)
            }
            Spacer(Modifier.width(28.dp))
            Column {
                MjText("竹间麻将", type.display, colors.onTable)
                Spacer(Modifier.height(6.dp))
                MjText("四人成局 · 清风入席", type.label, colors.onTable2)
                Spacer(Modifier.height(18.dp))
                LoadingDots(colors.onTable2)
                Spacer(Modifier.height(10.dp))
                MjText("国标 · 广东推倒胡 · 广东鸡平胡", type.caption, colors.onTable2.copy(alpha = 0.7f))
            }
        }
        MjText(
            "横屏对局",
            type.caption.copy(fontSize = type.caption.fontSize),
            colors.onTable2.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun LoadingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(Modifier.width(56.dp).height(10.dp)) {
        val gap = size.width / 4f
        for (i in 0..2) {
            val active = phase.toInt() == i
            drawCircle(
                color = color.copy(alpha = if (active) 0.95f else 0.3f),
                radius = if (active) 4.dp.toPx() * 0.5f else 3.dp.toPx() * 0.5f,
                center = androidx.compose.ui.geometry.Offset(gap * (i + 1) - gap / 2f + gap / 2f, size.height / 2f),
            )
        }
    }
}
