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
import mahjong.app.theme.TableMaterial
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 牌桌底：完全按主题表里的 [mahjong.app.theme.TableMaterial] 绘制台面工艺。
 *
 * 八款共用一条管线：木框（有的款为 0）→ 毡面径向渐变 → 织纹 → 上下压暗 → 1dp 内嵌线。
 * 织纹类型 / 密度 / 透明度 / 线宽 / 框厚 / 圆角全部来自主题表，渲染层不写死数值。
 */
fun Modifier.drawTableFelt(colors: MjColors, centerYPx: Float = -1f): Modifier = drawBehind {
    val material = colors.material
    // 小尺寸（设置页缩略图）按比例收木沿，避免 9dp 吃掉整块毡面。
    val rimPx = when {
        !material.rimSolid || material.rim <= 0.dp -> 0f
        size.height < 120.dp.toPx() -> min(size.height * 0.10f, material.rim.toPx())
        else -> material.rim.toPx()
    }
    val feltTopLeft = Offset(rimPx, rimPx)
    val feltSize = Size(size.width - rimPx * 2f, size.height - rimPx * 2f)
    val outerRadius = 14.dp.toPx()
    val feltRadius = if (rimPx > 0f) (outerRadius - rimPx).coerceAtLeast(2f) else 0f

    if (rimPx > 0f) {
        drawRoundRect(color = colors.tableRim, cornerRadius = CornerRadius(outerRadius))
    }

    // 绒面：径向渐变 t1 → t2 → t3，圆心跟实测桌心走，未测量时回落 38%。
    val centerY = if (centerYPx >= 0f) centerYPx else size.height * 0.38f
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(colors.table1, colors.table2, colors.table3),
            center = Offset(size.width * 0.5f, centerY),
            radius = max(size.width * 0.62f, size.height * 0.80f),
        ),
        topLeft = feltTopLeft,
        size = feltSize,
        cornerRadius = CornerRadius(feltRadius),
    )

    val feltPath = Path().apply {
        addRoundRect(RoundRect(Rect(feltTopLeft, feltSize), feltRadius, feltRadius))
    }
    clipPath(feltPath) {
        // 织纹：密度、线宽、明暗都由主题表给出。
        drawFeltTexture(colors, material)
        // 上下压暗：顶部 13%、底部 17%，中间不叠第二层遮罩。
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.13f),
                0.34f to Color.Transparent,
                0.68f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.17f),
            ),
        )
    }

    // 1dp 内嵌线：有实木框时压在毡面边缘（高光），无框时内缩 0.5dp 收边。
    val inset = if (material.rimSolid && rimPx > 0f) rimPx else 0.5.dp.toPx()
    drawRoundRect(
        color = colors.tableRimGlow,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius(if (rimPx > 0f) feltRadius else outerRadius),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * 绒面织纹：八种类型各一支画法，调用方已经裁好绒面。
 * 线宽统一用 dp，避免在高密度屏上退化成 0.3dp 的不可见细线。
 */
private fun DrawScope.drawFeltTexture(colors: MjColors, material: TableMaterial) {
    val line1 = colors.feltLine1
    val line2 = colors.feltLine2
    val width = material.lineWidth.toPx()
    val p1 = material.pitch.toPx()
    val p2 = material.pitch2.toPx()
    val p3 = material.pitch3.toPx()

    when (material.pattern) {
        // 45° 双向斜纹：4dp 间距，两向同色同透明度。
        FeltPattern.DIAG -> {
            drawAngledLines(45f, p1, line1, width)
            drawAngledLines(-45f, p1, line2, width)
        }

        // 冰裂开片：0° / 60° / 120° 三向，9 / 13 / 17dp。
        FeltPattern.CRACKLE -> {
            drawAngledLines(0f, p1, line1, width)
            drawAngledLines(60f, p2, line2, width)
            drawAngledLines(120f, p3, line2, width)
        }

        // 细亚麻：横向 4%、纵向 3%。
        FeltPattern.LINEN -> {
            drawAngledLines(0f, p1, line1, width)
            drawAngledLines(90f, p1, line2, width)
        }

        // 瓷砖格：0/90 同透明度。
        FeltPattern.GRID -> {
            drawAngledLines(0f, p1, line1, width)
            drawAngledLines(90f, p1, line1, width)
        }

        // 粗藤编：8dp 双经纬、2dp 粗线。
        FeltPattern.RATTAN -> {
            drawAngledLines(0f, p1, line1, width)
            drawAngledLines(90f, p1, line2, width)
        }

        // 扫描线：0° 3dp 亮线 + 90° 24dp 细纵线。
        FeltPattern.SCAN -> {
            drawAngledLines(0f, p1, line1, width)
            drawAngledLines(90f, p2, line2, width)
        }

        // 喷砂点阵：3dp 网格上的细颗粒，隔点压暗。
        FeltPattern.SAND -> {
            val radius = width
            var y = 0f
            var row = 0
            while (y <= size.height) {
                var x = if (row % 2 == 0) 0f else p1 * 0.5f
                while (x <= size.width) {
                    drawCircle(line1, radius = radius, center = Offset(x, y))
                    if (x + p1 * 0.5f <= size.width) {
                        drawCircle(line2, radius = radius * 0.8f, center = Offset(x + p1 * 0.5f, y))
                    }
                    x += p1
                }
                y += p1
                row++
            }
        }

        // 大漆：不画纹理，只留两处径向高光。
        FeltPattern.LACQUER -> {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(line1, Color.Transparent),
                    center = Offset(size.width * 0.30f, 0f),
                    radius = max(size.width * 1.10f, size.height * 0.70f),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(line2, Color.Transparent),
                    center = Offset(size.width * 0.78f, size.height * 1.08f),
                    radius = max(size.width * 0.80f, size.height * 0.50f),
                ),
            )
        }
    }
}

/**
 * 任意角度的等距线族：按法线方向铺线，长度覆盖画布对角线。
 * [spacing] 是垂直间距，与 CSS repeating-linear-gradient 的口径一致。
 */
private fun DrawScope.drawAngledLines(
    angleDeg: Float,
    spacing: Float,
    color: Color,
    width: Float,
) {
    if (spacing <= 0f || width <= 0f) return
    val a = angleDeg * PI.toFloat() / 180f
    val dx = cos(a)
    val dy = sin(a)
    val nx = -dy
    val ny = dx
    val cx = size.width / 2f
    val cy = size.height / 2f
    val len = hypot(size.width, size.height)
    val extent = (abs(size.width * nx) + abs(size.height * ny)) / 2f
    var t = -extent
    while (t <= extent) {
        val px = cx + nx * t
        val py = cy + ny * t
        drawLine(
            color = color,
            start = Offset(px - dx * len / 2f, py - dy * len / 2f),
            end = Offset(px + dx * len / 2f, py + dy * len / 2f),
            strokeWidth = width,
        )
        t += spacing
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
