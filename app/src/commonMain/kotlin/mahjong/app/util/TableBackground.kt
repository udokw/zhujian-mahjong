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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import mahjong.app.theme.EdgeStyle
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
 * 牌桌底：整屏只有这一层桌面，木框、内线、织纹都直接画在它上面。
 *
 * 1) 桌面（含木框/桌边）从屏幕左上角直接铺到右下角，四边不留房间底色；
 *    只有设置页缩略图按比例留一点房间边；
 * 2) 桌面圆角矩形只按物理屏幕内缩 6dp，木框按主题的 4~12dp 真实围一圈，
 *    外缘 1dp 亮线压边、内缘 1dp 高光收口，毡面在木框内开始；
 * 3) 无木框主题（青瓷 / 松石）也有 6dp 实体桌边：同族色平板 + 外缘亮线 + 内侧阴影线；
 * 4) 毡面只有主题自身的径向渐变 + 规格织纹，不叠内阴影、四周压暗或任何半透明遮罩。
 *
 * 桌面按物理屏幕铺满，不吃安全区：iPhone 横屏的刘海侧安全区
 * 由内容层让开，桌面本身不能被往中间挤，否则两侧会露出黑边。
 */
fun Modifier.drawTableFelt(
    colors: MjColors,
    centerYPx: Float = -1f,
): Modifier = drawBehind {
    val material = colors.material
    val dp1 = 1.dp.toPx()
    // 真机整屏铺满：桌面从 (0,0) 铺到屏幕右下角，上下左右不留任何房间底色；
    // 设置页缩略图才按比例留一点房间边，避免小图里木框吃掉整块毡面。
    val compact = size.height < 120.dp.toPx()
    val margin = if (compact) size.height * 0.04f else 0f
    val left = margin
    val top = margin
    val tableW = size.width - margin * 2f
    val tableH = size.height - margin * 2f
    if (tableW <= dp1 || tableH <= dp1) return@drawBehind

    val outerRadius = if (compact) min(tableH * 0.16f, 8.dp.toPx()) else 0f
    val rimPx = when {
        !material.rimSolid || material.rim <= 0.dp -> 0f
        compact -> min(tableH * 0.10f, material.rim.toPx())
        else -> material.rim.toPx()
    }
    // 无木框主题（青瓷 / 松石）的实体桌沿：厚度由主题表 edgeWidth 给出，
    // 青瓷 12dp 瓷沿、松石 10dp 石沿；设置页缩略图按高度比例收一点。
    val edgePx = when {
        rimPx > 0f || material.edgeWidth <= 0.dp -> 0f
        compact -> min(tableH * 0.09f, material.edgeWidth.toPx())
        else -> material.edgeWidth.toPx()
    }

    // 毡面几何：木框 / 实体桌沿共用一套；桌沿的内侧转角也按这个圆角走，
    // 保证「瓷盘托着绒面」的转角是连续的弧，不会出现方形内角。
    val bandPx = max(rimPx, edgePx)
    val feltLeft = left + bandPx
    val feltTop = top + bandPx
    val feltW = tableW - bandPx * 2f
    val feltH = tableH - bandPx * 2f
    val feltRadius = if (bandPx > 0f) {
        if (compact) (outerRadius - bandPx).coerceAtLeast(2f) else max(bandPx + 4.dp.toPx(), 12.dp.toPx())
    } else {
        outerRadius
    }
    val feltRect = Rect(Offset(feltLeft, feltTop), Size(feltW, feltH))
    val feltPath = Path().apply {
        addRoundRect(RoundRect(feltRect, feltRadius, feltRadius))
    }

    // 1) 房间底：只有缩略图需要；真机铺满整屏，不画任何房间色，保证四边都是桌面色。
    if (margin > 0f) {
        val room = lerp(lerp(colors.tableRim, colors.table3, 0.35f), Color.Black, 0.42f)
        drawRect(color = room)
    }

    // 2) 木框：回到 1.15 的实木做法——斜向双色渐变 + 斜向木纹线，不是一块纯色。
    if (rimPx > 0f) {
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    lerp(colors.tableRim, Color.White, 0.12f),
                    colors.tableRim,
                    lerp(colors.tableRim, Color.Black, 0.20f),
                ),
                start = Offset(left, top),
                end = Offset(left + tableW, top + tableH),
            ),
            topLeft = Offset(left, top),
            size = Size(tableW, tableH),
            cornerRadius = CornerRadius(outerRadius),
        )
        // 斜向木纹：22dp 一道，只在露出的木框上可见（毡面随后整块盖住）。
        val grain = lerp(colors.tableRim, Color.White, 0.22f).copy(alpha = 0.14f)
        val grainGap = 22.dp.toPx()
        var gx = -tableH
        while (gx < tableW) {
            drawLine(
                color = grain,
                start = Offset(left + gx, top),
                end = Offset(left + gx + tableH, top + tableH),
                strokeWidth = 1.2.dp.toPx(),
            )
            gx += grainGap
        }
        drawRoundRect(
            color = lerp(colors.tableRim, Color.White, 0.26f),
            topLeft = Offset(left + dp1 * 0.5f, top + dp1 * 0.5f),
            size = Size(tableW - dp1, tableH - dp1),
            cornerRadius = CornerRadius(outerRadius),
            style = Stroke(width = dp1),
        )
    } else if (edgePx > 0f) {
        // 实体桌沿：整块料 + 四边垂直渐变 + 转角斜接 + 材质纹理，厚度肉眼可见。
        drawSolidEdgeBase(colors, material, left, top, tableW, tableH, edgePx, feltRect, feltRadius, outerRadius)
    }

    // 3) 毡面：从木框 / 实体桌边内开始，大半径径向光按 1.15 的口径铺开。
    val centerY = if (centerYPx >= 0f) centerYPx else top + tableH * 0.36f
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(colors.table1, colors.table2, colors.table3),
            center = Offset(left + tableW * 0.5f, centerY),
            radius = max(tableW, tableH) * 0.95f,
        ),
        topLeft = Offset(feltLeft, feltTop),
        size = Size(feltW, feltH),
        cornerRadius = CornerRadius(feltRadius),
    )

    clipPath(feltPath) {
        // 绒面顶部柔光（1.15 的灯打在桌布上的那层），只在毡面内。
        drawRect(
            brush = Brush.verticalGradient(
                0f to colors.onTable.copy(alpha = 0.05f),
                0.5f to Color.Transparent,
            ),
        )
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
    }

    // 4) 内嵌线：有实木框时压在毡面边缘；经典多一道金线 + 内缩 3dp 的白线。
    val accent = material.accent
    if (accent != null && rimPx > 0f) {
        drawRoundRect(
            color = Color(accent),
            topLeft = Offset(feltLeft, feltTop),
            size = Size(feltW, feltH),
            cornerRadius = CornerRadius(feltRadius),
            style = Stroke(width = 1.6.dp.toPx()),
        )
        val inner = 3.dp.toPx()
        drawRoundRect(
            color = colors.tableRimGlow,
            topLeft = Offset(feltLeft + inner, feltTop + inner),
            size = Size(feltW - inner * 2f, feltH - inner * 2f),
            cornerRadius = CornerRadius((feltRadius - inner).coerceAtLeast(2f)),
            style = Stroke(width = dp1),
        )
    } else if (rimPx > 0f) {
        // 有框：1dp 白/墨高光压在木框与毡面交界。
        drawRoundRect(
            color = colors.tableRimGlow,
            topLeft = Offset(left + rimPx, top + rimPx),
            size = Size(tableW - rimPx * 2f, tableH - rimPx * 2f),
            cornerRadius = CornerRadius(feltRadius),
            style = Stroke(width = dp1),
        )
    } else if (edgePx > 0f) {
        // 无框：外缘提亮 + 材质高光/倒角 + 内侧方向性阴影，靠这三笔立起体积。
        drawSolidEdgeTop(
            colors = colors, material = material,
            left = left, top = top, tableW = tableW, tableH = tableH, edgePx = edgePx,
            feltRect = feltRect, feltRadius = feltRadius, feltPath = feltPath,
        )
    }
}

/**
 * 实体桌沿（青瓷瓷沿 / 松石石沿）的底色与材质。
 *
 * 做法与设计稿一致：四条边各铺一条「外亮内暗」的垂直渐变，转角用三角斜接补色，
 * 得到一块连续的、有厚度的料；再在环带内撒材质纹理（瓷沿开片 / 石沿细颗粒）。
 * 环底先铺内色，毡面圆角外侧的转角楔形因此不会露出房间底色。
 */
private fun DrawScope.drawSolidEdgeBase(
    colors: MjColors,
    material: TableMaterial,
    left: Float,
    top: Float,
    tableW: Float,
    tableH: Float,
    edgePx: Float,
    feltRect: Rect,
    feltRadius: Float,
    outerRadius: Float,
) {
    if (outerRadius > 0f) {
        // 设置页缩略图：桌沿外缘也收成圆角，跟房间底一致。
        val outline = Path().apply {
            addRoundRect(RoundRect(Rect(Offset(left, top), Size(tableW, tableH)), outerRadius, outerRadius))
        }
        clipPath(outline) {
            drawSolidEdgeFill(colors, material, left, top, tableW, tableH, edgePx, feltRect, feltRadius)
        }
        return
    }
    drawSolidEdgeFill(colors, material, left, top, tableW, tableH, edgePx, feltRect, feltRadius)
}

private fun DrawScope.drawSolidEdgeFill(
    colors: MjColors,
    material: TableMaterial,
    left: Float,
    top: Float,
    tableW: Float,
    tableH: Float,
    edgePx: Float,
    feltRect: Rect,
    feltRadius: Float,
) {
    val outer = colors.tableEdge
    val inner = colors.tableEdge2
    val e = edgePx
    drawRect(color = inner, topLeft = Offset(left, top), size = Size(tableW, tableH))
    val gLeft = Brush.linearGradient(listOf(outer, inner), Offset(left, top), Offset(left + e, top))
    val gRight = Brush.linearGradient(listOf(outer, inner), Offset(left + tableW, top), Offset(left + tableW - e, top))
    val gTop = Brush.linearGradient(listOf(outer, inner), Offset(left, top), Offset(left, top + e))
    val gBottom = Brush.linearGradient(listOf(outer, inner), Offset(left, top + tableH), Offset(left, top + tableH - e))
    drawRect(gLeft, Offset(left, top), Size(e, tableH))
    drawRect(gRight, Offset(left + tableW - e, top), Size(e, tableH))
    drawRect(gTop, Offset(left, top), Size(tableW, e))
    drawRect(gBottom, Offset(left, top + tableH - e), Size(tableW, e))

    // 转角斜接：把与侧边渐变一致的那半块三角形补回来，避免竖边/横边硬接。
    fun miter(a: Offset, b: Offset, c: Offset, brush: Brush) {
        val tri = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            close()
        }
        clipPath(tri) { drawRect(brush, Offset(left, top), Size(tableW, tableH)) }
    }
    miter(Offset(left, top), Offset(left + e, top + e), Offset(left, top + e), gLeft)
    miter(Offset(left + tableW, top), Offset(left + tableW - e, top + e), Offset(left + tableW, top + e), gRight)
    miter(Offset(left, top + tableH), Offset(left + e, top + tableH - e), Offset(left, top + tableH - e), gLeft)
    miter(
        Offset(left + tableW, top + tableH),
        Offset(left + tableW - e, top + tableH - e),
        Offset(left + tableW, top + tableH - e),
        gRight,
    )

    // 材质纹理只落在环带内，毡面不受影响。
    val ring = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset(left, top), Size(tableW, tableH)))
        addRoundRect(RoundRect(feltRect, feltRadius, feltRadius))
    }
    clipPath(ring) {
        drawEdgeTexture(material, outer, left, top, e, tableW, tableH)
    }
}

/** 桌沿材质：瓷沿细开片（0/62/118°），石沿石绿细颗粒；调用方已裁到环带内。 */
private fun DrawScope.drawEdgeTexture(
    material: TableMaterial,
    outer: Color,
    left: Float,
    top: Float,
    edgePx: Float,
    tableW: Float,
    tableH: Float,
) {
    when (material.edgeStyle) {
        EdgeStyle.CERAMIC -> {
            // 釉面开片比毡面更淡，只是让瓷沿有一点手工窑变的痕迹。
            val crack = lerp(outer, Color(0xFF233830), 0.62f)
            drawAngledLines(0f, 26.dp.toPx(), crack.copy(alpha = 0.07f), 0.55.dp.toPx())
            drawAngledLines(62f, 34.dp.toPx(), crack.copy(alpha = 0.055f), 0.5.dp.toPx())
            drawAngledLines(118f, 42.dp.toPx(), crack.copy(alpha = 0.05f), 0.5.dp.toPx())
        }

        EdgeStyle.STONE -> {
            // 确定性散点（线性同余）：沿外圈周长均布、再往内压深度，
            // 保证颗粒疏密一致，且每次绘制完全一样。
            var seed = 7L
            fun rnd(): Float {
                seed = (seed * 16807L) % 2147483647L
                return seed / 2147483647f
            }
            val light = Color(0xFFE4F5EE).copy(alpha = 0.10f)
            val dark = Color(0xFF05201C).copy(alpha = 0.12f)
            val ringArea = tableW * tableH - (tableW - edgePx * 2f).coerceAtLeast(0f) *
                (tableH - edgePx * 2f).coerceAtLeast(0f)
            val cell = 4.2.dp.toPx()
            val count = (ringArea / (cell * cell)).toInt().coerceIn(60, 2600)
            val per = 2f * (tableW + tableH)
            val minR = 0.45.dp.toPx()
            val maxR = 1.15.dp.toPx()
            repeat(count) {
                val depth = rnd() * edgePx
                val t = rnd() * per
                val px: Float
                val py: Float
                when {
                    t < tableW -> {
                        px = t
                        py = depth
                    }
                    t < tableW + tableH -> {
                        px = tableW - depth
                        py = t - tableW
                    }
                    t < tableW * 2f + tableH -> {
                        px = tableW - (t - tableW - tableH)
                        py = tableH - depth
                    }
                    else -> {
                        px = depth
                        py = tableH - (t - tableW * 2f - tableH)
                    }
                }
                drawCircle(
                    color = if (rnd() > 0.5f) light else dark,
                    radius = minR + rnd() * (maxR - minR),
                    center = Offset(left + px, top + py),
                )
            }
        }

        EdgeStyle.NONE -> Unit
    }
}

/**
 * 实体桌沿的收口三笔：外缘提亮、材质高光/倒角、毡面内侧方向性阴影。
 * 青瓷是高光带 + 次高光（釉面），松石是细反光 + 斜切倒角暗折（石材）。
 */
private fun DrawScope.drawSolidEdgeTop(
    colors: MjColors,
    material: TableMaterial,
    left: Float,
    top: Float,
    tableW: Float,
    tableH: Float,
    edgePx: Float,
    feltRect: Rect,
    feltRadius: Float,
    feltPath: Path,
) {
    // 收口线的圆角随内缩从 0 过渡到毡面圆角，转角处是连贯的弧。
    fun ring(inset: Float, color: Color, width: Float) {
        drawRoundRect(
            color = color,
            topLeft = Offset(left + inset, top + inset),
            size = Size(tableW - inset * 2f, tableH - inset * 2f),
            cornerRadius = CornerRadius((feltRadius * inset / edgePx).coerceAtLeast(0f)),
            style = Stroke(width = width),
        )
    }
    ring(0.45.dp.toPx(), Color.White.copy(alpha = 0.50f), 0.9.dp.toPx())
    when (material.edgeStyle) {
        EdgeStyle.CERAMIC -> {
            ring(3.dp.toPx(), Color.White.copy(alpha = 0.42f), 2.4.dp.toPx())
            ring(6.4.dp.toPx(), Color.White.copy(alpha = 0.14f), 1.1.dp.toPx())
        }
        EdgeStyle.STONE -> {
            ring(2.2.dp.toPx(), Color.White.copy(alpha = 0.18f), 1.2.dp.toPx())
            ring(6.6.dp.toPx(), Color.Black.copy(alpha = 0.22f), 1.2.dp.toPx())
        }
        EdgeStyle.NONE -> Unit
    }

    // 毡面四周一圈方向性压暗：桌沿像一块料压住绒面，绒面是嵌进去的。
    val depth = if (material.edgeStyle == EdgeStyle.CERAMIC) 6.2.dp.toPx() else 5.3.dp.toPx()
    val shade = Color(0xFF0A1C16)
    val near = shade.copy(alpha = 0.34f)
    val far = shade.copy(alpha = 0.13f)
    clipPath(feltPath) {
        drawRect(
            brush = Brush.linearGradient(
                0f to near, 0.45f to far, 1f to Color.Transparent,
                start = Offset(feltRect.left, feltRect.top),
                end = Offset(feltRect.left, feltRect.top + depth),
            ),
            topLeft = Offset(feltRect.left, feltRect.top),
            size = Size(feltRect.width, depth),
        )
        drawRect(
            brush = Brush.linearGradient(
                0f to near, 0.45f to far, 1f to Color.Transparent,
                start = Offset(feltRect.left, feltRect.bottom),
                end = Offset(feltRect.left, feltRect.bottom - depth),
            ),
            topLeft = Offset(feltRect.left, feltRect.bottom - depth),
            size = Size(feltRect.width, depth),
        )
        drawRect(
            brush = Brush.linearGradient(
                0f to near, 0.45f to far, 1f to Color.Transparent,
                start = Offset(feltRect.left, feltRect.top),
                end = Offset(feltRect.left + depth, feltRect.top),
            ),
            topLeft = Offset(feltRect.left, feltRect.top),
            size = Size(depth, feltRect.height),
        )
        drawRect(
            brush = Brush.linearGradient(
                0f to near, 0.45f to far, 1f to Color.Transparent,
                start = Offset(feltRect.right, feltRect.top),
                end = Offset(feltRect.right - depth, feltRect.top),
            ),
            topLeft = Offset(feltRect.right - depth, feltRect.top),
            size = Size(depth, feltRect.height),
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
