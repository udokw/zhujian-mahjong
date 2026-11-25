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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
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
 * 桌面不再是铺满屏幕的一块布，而是一张有厚度的实体桌：
 * 1) 整屏先铺「房间」底色（比毡面更暗的同色系），四周自然留出桌沿；
 * 2) 桌面圆角矩形从 [edgeInsets] 内缩 6dp 开始，木框按主题的 4~12dp 真实围一圈，
 *    外缘 1dp 亮线压边、内缘 1dp 高光收口，毡面在木框内开始；
 * 3) 无框主题同样有圆角桌面边界 + 清晰的 1dp 内线，不再无限铺满；
 * 4) 毡面内缘加一圈柔和内阴影制造下沉厚度，织纹按规格密度画满。
 *
 * [edgeInsets] 传系统安全区（状态栏 / 手势条 / 刘海）的实际 dp 像素，
 * 保证桌沿落在安全区内，不会被设备圆角或系统栏吃掉。
 */
fun Modifier.drawTableFelt(
    colors: MjColors,
    centerYPx: Float = -1f,
    edgeInsets: Rect = Rect.Zero,
): Modifier = drawBehind {
    val material = colors.material
    val dp1 = 1.dp.toPx()
    // 小尺寸（设置页缩略图）整体按比例收边，避免 9dp 木框吃掉整块毡面。
    val compact = size.height < 120.dp.toPx()
    val margin = if (compact) size.height * 0.05f else 6.dp.toPx()
    val left = edgeInsets.left + margin
    val top = edgeInsets.top + margin
    val tableW = size.width - edgeInsets.left - edgeInsets.right - margin * 2f
    val tableH = size.height - edgeInsets.top - edgeInsets.bottom - margin * 2f
    if (tableW <= dp1 || tableH <= dp1) return@drawBehind

    val outerRadius = if (compact) min(tableH * 0.16f, 8.dp.toPx()) else 14.dp.toPx()
    val rimPx = when {
        !material.rimSolid || material.rim <= 0.dp -> 0f
        compact -> min(tableH * 0.10f, material.rim.toPx())
        else -> material.rim.toPx()
    }

    // 1) 房间底：毡面边缘再压暗，桌面自然浮出来。
    val room = lerp(colors.table3, Color.Black, 0.72f)
    drawRect(
        brush = Brush.verticalGradient(
            0f to lerp(room, Color.White, 0.06f),
            0.55f to room,
            1f to lerp(room, Color.Black, 0.40f),
        ),
    )

    // 2) 木框：实色压边 + 上下轻微明暗，外缘 1dp 亮线把桌沿和房间分开。
    if (rimPx > 0f) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    lerp(colors.tableRim, Color.White, 0.10f),
                    colors.tableRim,
                    lerp(colors.tableRim, Color.Black, 0.18f),
                ),
                startY = top,
                endY = top + tableH,
            ),
            topLeft = Offset(left, top),
            size = Size(tableW, tableH),
            cornerRadius = CornerRadius(outerRadius),
        )
        drawRoundRect(
            color = lerp(colors.tableRim, Color.White, 0.26f),
            topLeft = Offset(left + dp1 * 0.5f, top + dp1 * 0.5f),
            size = Size(tableW - dp1, tableH - dp1),
            cornerRadius = CornerRadius(outerRadius),
            style = Stroke(width = dp1),
        )
    }

    // 3) 毡面：从木框内开始，圆心跟实测桌心走，未测量时回落 38%。
    val feltLeft = left + rimPx
    val feltTop = top + rimPx
    val feltW = tableW - rimPx * 2f
    val feltH = tableH - rimPx * 2f
    val feltRadius = if (rimPx > 0f) (outerRadius - rimPx).coerceAtLeast(2f) else outerRadius
    val centerY = if (centerYPx >= 0f) centerYPx else top + tableH * 0.38f
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(colors.table1, colors.table2, colors.table3),
            center = Offset(left + tableW * 0.5f, centerY),
            radius = max(tableW * 0.62f, tableH * 0.80f),
        ),
        topLeft = Offset(feltLeft, feltTop),
        size = Size(feltW, feltH),
        cornerRadius = CornerRadius(feltRadius),
    )

    val feltPath = Path().apply {
        addRoundRect(RoundRect(Rect(Offset(feltLeft, feltTop), Size(feltW, feltH)), feltRadius, feltRadius))
    }
    clipPath(feltPath) {
        // 织纹：密度、线宽、明暗都由主题表给出；先画一层 1dp 下的暗线做压纹浮雕，
        // 再画亮线，5% 的规格值也能在真机上肉眼分辨。
        if (material.pattern != FeltPattern.LACQUER) {
            translate(top = 1.dp.toPx()) {
                drawFeltTexture(
                    material,
                    Color.Black.copy(alpha = min(1f, material.alpha1 * 1.2f)),
                    Color.Black.copy(alpha = min(1f, material.alpha2 * 1.2f)),
                )
            }
        }
        drawFeltTexture(material, colors.feltLine1, colors.feltLine2)

        // 内阴影：毡面贴着木框的一圈压暗，做出下沉厚度。
        val shadow = min(feltW, feltH) * 0.055f
        if (shadow > 0f) {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.10f),
                topLeft = Offset(feltLeft, feltTop),
                size = Size(feltW, feltH),
                cornerRadius = CornerRadius(feltRadius),
                style = Stroke(width = shadow * 2f),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.07f),
                topLeft = Offset(feltLeft, feltTop),
                size = Size(feltW, feltH),
                cornerRadius = CornerRadius(feltRadius),
                style = Stroke(width = shadow * 0.7f),
            )
        }

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

    // 4) 内嵌线：有实木框时压在毡面边缘；经典多一道金线 + 内缩 3dp 的白线。
    val accent = material.accent
    if (accent != null && rimPx > 0f) {
        drawRoundRect(
            color = Color(accent),
            topLeft = Offset(feltLeft, feltTop),
            size = Size(feltW, feltH),
            cornerRadius = CornerRadius(feltRadius),
            style = Stroke(width = 1.4.dp.toPx()),
        )
        val inner = 3.dp.toPx()
        drawRoundRect(
            color = colors.tableRimGlow,
            topLeft = Offset(feltLeft + inner, feltTop + inner),
            size = Size(feltW - inner * 2f, feltH - inner * 2f),
            cornerRadius = CornerRadius((feltRadius - inner).coerceAtLeast(2f)),
            style = Stroke(width = dp1),
        )
    } else {
        // 有框：1dp 白/墨高光压在木框与毡面交界；无框：内缩 1dp 收一道清晰内线。
        val inset = if (rimPx > 0f) rimPx else dp1
        drawRoundRect(
            color = colors.tableRimGlow,
            topLeft = Offset(left + inset, top + inset),
            size = Size(tableW - inset * 2f, tableH - inset * 2f),
            cornerRadius = CornerRadius(
                if (rimPx > 0f) feltRadius else (outerRadius - dp1).coerceAtLeast(2f),
            ),
            style = Stroke(width = dp1),
        )
    }
}

/**
 * 绒面织纹：九种类型各一支画法，调用方已经裁好绒面。
 * 线宽统一用 dp，避免在高密度屏上退化成 0.3dp 的不可见细线。
 */
private fun DrawScope.drawFeltTexture(material: TableMaterial, line1: Color, line2: Color) {
    val width = material.lineWidth.toPx()
    val p1 = material.pitch.toPx()
    val p2 = material.pitch2.toPx()
    val p3 = material.pitch3.toPx()

    when (material.pattern) {
        // 经典细格：0/90 双向 10dp，经纬同透明度的细织纹。
        FeltPattern.WEAVE -> {
            drawAngledLines(0f, p1, line1, width)
            drawAngledLines(90f, p1, line2, width)
        }

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
