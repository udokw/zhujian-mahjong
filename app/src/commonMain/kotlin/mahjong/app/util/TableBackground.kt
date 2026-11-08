package mahjong.app.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import mahjong.app.theme.MjColors
import kotlin.math.max
import kotlin.math.min

/**
 * 牌桌底：实木桌沿 + 内嵌绒面桌布 + 中央罗盘环。
 *
 * 外圈用木纹渐变模拟实木边框，内圈用径向渐变做绒面，金色双线收边，
 * 让牌桌从“一块绿色背景”变成有厚度、有材质的实体桌面。
 */
fun Modifier.drawTableFelt(colors: MjColors, centerYPx: Float = -1f): Modifier = drawBehind {
    // 1) 实木桌沿：整块木色打底，斜向木纹只露在四周。
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(colors.wood1, colors.wood2),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
    )
    val grainGap = 22.dp.toPx()
    var gx = -size.height
    while (gx < size.width) {
        drawLine(
            color = colors.woodGrain.copy(alpha = 0.16f),
            start = Offset(gx, 0f),
            end = Offset(gx + size.height, size.height),
            strokeWidth = 1.2f,
        )
        gx += grainGap
    }

    // 2) 内嵌绒面：四周留出 12dp 木边，圆角收口。
    val inset = 12.dp.toPx()
    val feltTopLeft = Offset(inset, inset)
    val feltSize = Size(size.width - inset * 2f, size.height - inset * 2f)
    val feltRadius = CornerRadius(16.dp.toPx())
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(colors.table1, colors.table2, colors.table3),
            center = Offset(size.width * 0.5f, size.height * 0.34f),
            radius = max(size.width, size.height) * 0.95f,
        ),
        topLeft = feltTopLeft,
        size = feltSize,
        cornerRadius = feltRadius,
    )
    // 绒面顶部柔光
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to colors.onTable.copy(alpha = 0.05f),
            0.5f to Color.Transparent,
        ),
        topLeft = feltTopLeft,
        size = feltSize,
        cornerRadius = feltRadius,
    )
    // 金色内框 + 米白细线，做出桌布压边
    drawRoundRect(
        color = colors.tableGold.copy(alpha = if (colors.isDark) 0.22f else 0.30f),
        topLeft = feltTopLeft,
        size = feltSize,
        cornerRadius = feltRadius,
        style = Stroke(width = 1.6.dp.toPx()),
    )
    drawRoundRect(
        color = colors.tableLine,
        topLeft = Offset(inset + 3.dp.toPx(), inset + 3.dp.toPx()),
        size = Size(feltSize.width - 6.dp.toPx(), feltSize.height - 6.dp.toPx()),
        cornerRadius = CornerRadius(13.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )

    // 3) 绒面织纹：极淡网格只铺在绒面范围内，不压到木边。
    val gap = 32.dp.toPx()
    val line = colors.onTable.copy(alpha = 0.016f)
    var x = inset + gap
    while (x < size.width - inset) {
        drawLine(line, Offset(x, inset), Offset(x, size.height - inset), 1f)
        x += gap
    }
    var y = inset + gap
    while (y < size.height - inset) {
        drawLine(line, Offset(inset, y), Offset(size.width - inset, y), 1f)
        y += gap
    }

    // 4) 中央罗盘环：只做压纹，不投影、不压住牌河。
    // centerYPx 由牌桌实测传入（HUD 底边与手牌分割线的中点），未测量时回落到正中心。
    val centerY = if (centerYPx >= 0f) centerYPx else size.height * 0.5f
    val center = Offset(size.width * 0.5f, centerY)
    val ring = min(size.width, size.height) * 0.28f
    drawCircle(colors.tableGold.copy(alpha = 0.07f), radius = ring, center = center)
    drawCircle(
        color = colors.tableLine,
        radius = ring,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = colors.tableGold.copy(alpha = 0.22f),
        radius = ring * 0.62f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )

    // 5) 整体压暗，上下略深，稳住画面重心。
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.16f),
            0.35f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.22f),
        ),
    )
}

/**
 * 页面底：主页/设置/记录等非牌桌页面使用的主题背景。
 * 纵向渐变 + 竹节竖纹 + 右上暖光 + 左下暗角，替代纯色平铺。
 */
fun Modifier.drawPageBackdrop(colors: MjColors): Modifier = drawBehind {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(colors.surface2, colors.bg, colors.bg),
        ),
    )
    // 竹节竖纹
    val gap = 46.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(
            color = colors.line.copy(alpha = if (colors.isDark) 0.45f else 0.75f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
        x += gap
    }
    // 右上金色暖光，像灯打在桌面上
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(colors.gold.copy(alpha = if (colors.isDark) 0.10f else 0.07f), Color.Transparent),
            center = Offset(size.width * 0.82f, -size.height * 0.08f),
            radius = size.width * 0.85f,
        ),
    )
    // 底部暗角
    drawRect(
        brush = Brush.verticalGradient(
            0.62f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (colors.isDark) 0.18f else 0.07f),
        ),
    )
}
