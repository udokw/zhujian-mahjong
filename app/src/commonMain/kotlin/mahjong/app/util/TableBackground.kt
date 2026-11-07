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
 * 牌桌底：径向渐变 + 桌沿描边 + 中央罗盘环 + 极淡网格，对应 design-system 的桌面色规范。
 */
fun Modifier.drawTableFelt(colors: MjColors): Modifier = drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(colors.table1, colors.table2, colors.table3),
            center = Offset(size.width * 0.5f, size.height * 0.34f),
            radius = max(size.width, size.height) * 0.95f,
        ),
    )
    drawCircle(
        color = colors.onTable.copy(alpha = if (colors.isDark) 0.035f else 0.05f),
        radius = size.width * 0.62f,
        center = Offset(size.width * 0.5f, size.height * 0.14f),
    )
    val gap = 32.dp.toPx()
    val line = colors.onTable.copy(alpha = 0.016f)
    var x = 0f
    while (x < size.width) {
        drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
        x += gap
    }
    var y = 0f
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
        y += gap
    }

    // 中央罗盘环：只做压纹，不投影、不压住牌河。
    val center = Offset(size.width * 0.5f, size.height * 0.40f)
    val ring = min(size.width, size.height) * 0.30f
    drawCircle(colors.tableGold.copy(alpha = 0.06f), radius = ring, center = center)
    drawCircle(
        color = colors.tableLine,
        radius = ring,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = colors.tableGold.copy(alpha = 0.14f),
        radius = ring * 0.62f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )

    // 桌沿：一圈内嵌金线 + 四角记号，强化实木牌桌的边界。
    val inset = 7.dp.toPx()
    val rim = Size(size.width - inset * 2f, size.height - inset * 2f)
    drawRoundRect(
        color = colors.tableGold.copy(alpha = if (colors.isDark) 0.14f else 0.20f),
        topLeft = Offset(inset, inset),
        size = rim,
        cornerRadius = CornerRadius(20.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawRoundRect(
        color = colors.tableLine,
        topLeft = Offset(inset + 3.dp.toPx(), inset + 3.dp.toPx()),
        size = Size(rim.width - 6.dp.toPx(), rim.height - 6.dp.toPx()),
        cornerRadius = CornerRadius(17.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )

    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.16f),
            0.35f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.22f),
        ),
    )
}
