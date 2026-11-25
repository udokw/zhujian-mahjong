package mahjong.app.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import mahjong.app.theme.FeltPattern
import mahjong.app.theme.MjColors
import mahjong.app.theme.TableSkin
import mahjong.app.theme.themeSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 牌桌底：实木桌沿 + 内嵌绒面桌布 + 中央罗盘环。
 *
 * 外圈用木纹渐变模拟实木边框，内圈用径向渐变做绒面，金色双线收边，
 * 让牌桌从“一块绿色背景”变成有厚度、有材质的实体桌面。
 */
fun Modifier.drawTableFelt(colors: MjColors, centerYPx: Float = -1f): Modifier = drawBehind {
    if (colors.skin == TableSkin.WOOD) {
        drawWoodTable(colors)
        return@drawBehind
    }
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

    // 3) 绒面织纹：按主题换织法（平织 / 亚麻 / 暗花 / 拉丝 / 藤编），只在绒面内绘制。
    drawFeltTexture(colors, feltTopLeft, feltSize, feltRadius)

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
 * 暖木台面：9dp 胡桃木实色压边 + 内嵌 1dp 高光，毡面径向渐变（圆心 38%），
 * 双向 45° 粗毡斜纹。中央盘由 CenterInfo 画在文字下方，和圆心共用同一套测量。
 */
private fun DrawScope.drawWoodTable(colors: MjColors) {
    // 外圈木框：实色压边，不用投影/木纹叠加。
    drawRoundRect(
        color = colors.tableRim,
        cornerRadius = CornerRadius(14.dp.toPx()),
    )
    // 小尺寸（设置页预览）按比例收边，避免 9dp 在 34dp 高的缩略图里吃掉整块毡面。
    val rimPx = if (size.height < 120.dp.toPx()) size.height * 0.10f else 9.dp.toPx()
    val feltTopLeft = Offset(rimPx, rimPx)
    val feltSize = Size(size.width - rimPx * 2f, size.height - rimPx * 2f)
    val feltRadius = CornerRadius((14.dp.toPx() - rimPx).coerceAtLeast(2f))

    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(colors.table1, colors.table2, colors.table3),
            center = Offset(size.width * 0.5f, size.height * 0.38f),
            radius = max(size.width * 0.62f, size.height * 0.80f),
        ),
        topLeft = feltTopLeft,
        size = feltSize,
        cornerRadius = feltRadius,
    )

    // 粗毡斜纹：±45°、1dp 线、垂直间距 4dp，沿 x 的步长为 4·√2。
    clipPath(Path().apply { addRoundRect(RoundRect(Rect(feltTopLeft, feltSize), feltRadius)) }) {
        val step = 4.dp.toPx() * sqrt(2f)
        var x = -size.height
        while (x <= size.width + size.height) {
            drawLine(colors.feltStripe, Offset(x, 0f), Offset(x + size.height, size.height), 1f)
            drawLine(colors.feltStripe, Offset(x, 0f), Offset(x - size.height, size.height), 1f)
            x += step
        }
    }

    // 木框内嵌 1dp 高光，压住毡面与木框的交界。
    drawRoundRect(
        color = colors.tableRimGlow,
        topLeft = feltTopLeft,
        size = feltSize,
        cornerRadius = feltRadius,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * 绒面织纹：八款主题共用几何，只按 [mahjong.app.theme.themeSpecs] 里声明的织法换笔触。
 * 全部是 1px 短线或小圆环，密度控制在每帧数百条以内。
 */
private fun DrawScope.drawFeltTexture(
    colors: MjColors,
    topLeft: Offset,
    feltSize: Size,
    radius: CornerRadius,
) {
    val pattern = themeSpec(colors.skin).pattern
    if (pattern == FeltPattern.PLAIN) return
    clipPath(Path().apply { addRoundRect(RoundRect(Rect(topLeft, feltSize), radius)) }) {
        val tint = colors.onTable.copy(alpha = 0.018f)
        val width = size.width
        val height = size.height
        when (pattern) {
            FeltPattern.WEAVE -> {
                val gap = 10.dp.toPx()
                var x = 0f
                while (x < width) {
                    drawLine(tint, Offset(x, 0f), Offset(x, height), 1f)
                    x += gap
                }
                var y = 0f
                while (y < height) {
                    drawLine(tint, Offset(0f, y), Offset(width, y), 1f)
                    y += gap
                }
            }

            FeltPattern.LINEN -> {
                val gap = 14.dp.toPx()
                var x = -height
                while (x < width + height) {
                    drawLine(tint, Offset(x, 0f), Offset(x + height, height), 1f)
                    drawLine(tint, Offset(x, 0f), Offset(x - height, height), 1f)
                    x += gap
                }
            }

            FeltPattern.DAMASK -> {
                // 暗花：斜向错位的小圆环，像提花织物的重复团花。
                val gap = 42.dp.toPx()
                val r = 2.6.dp.toPx()
                val ring = colors.onTable.copy(alpha = 0.030f)
                var row = 0
                var y = gap * 0.5f
                while (y < height) {
                    var x = if (row % 2 == 0) gap * 0.5f else gap
                    while (x < width) {
                        drawCircle(ring, radius = r, center = Offset(x, y), style = Stroke(1f))
                        x += gap
                    }
                    y += gap * 0.72f
                    row++
                }
            }

            FeltPattern.BRUSHED -> {
                val gap = 6.dp.toPx()
                val streak = colors.onTable.copy(alpha = 0.012f)
                var y = 0f
                while (y < height) {
                    drawLine(streak, Offset(0f, y), Offset(width, y), 1f)
                    y += gap
                }
            }

            FeltPattern.RATTAN -> {
                val gap = 13.dp.toPx()
                val weave = colors.onTable.copy(alpha = 0.032f)
                var x = 0f
                while (x < width) {
                    drawLine(weave, Offset(x, 0f), Offset(x, height), 1.4f)
                    x += gap
                }
                var y = 0f
                while (y < height) {
                    drawLine(weave, Offset(0f, y), Offset(width, y), 1.4f)
                    y += gap
                }
            }

            FeltPattern.LACQUER -> {
                // 漆面：不画织纹，只加一道横向柔光，配顶部高光形成反光。
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0.12f to Color.Transparent,
                        0.30f to colors.onTable.copy(alpha = 0.030f),
                        0.46f to Color.Transparent,
                    ),
                    topLeft = topLeft,
                    size = feltSize,
                    cornerRadius = radius,
                )
            }

            FeltPattern.PLAIN -> Unit
        }
    }
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
