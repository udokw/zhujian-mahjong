package mahjong.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.MjColors
import mahjong.app.theme.MjRadius
import mahjong.core.tile.Tiles

enum class TileVisual { NORMAL, SELECTED, JUST_PLAYED, WINNABLE, RECOMMENDED }

/** 唯一的牌面实现：按 60×80 viewBox 等比缩放的矢量牌。 */
@Composable
fun MjTile(
    tile: Int,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = width * 4f / 3f,
    visual: TileVisual = TileVisual.NORMAL,
    faceDown: Boolean = false,
    dimmed: Boolean = false,
) {
    val colors = LocalMjColors.current
    val measurer = rememberTextMeasurer(cacheSize = 48)
    Canvas(modifier.size(width, height)) {
        val s = size.width / 60f
        if (faceDown) {
            drawTileBack(colors, s)
            return@Canvas
        }
        drawTileFace(colors, visual, dimmed, s)
        if (!dimmed) drawGlyph(tile, s, measurer, colors)
    }
}

@Composable
fun MjBackTile(width: Dp, height: Dp, modifier: Modifier = Modifier, muted: Boolean = false) {
    val colors = LocalMjColors.current
    Canvas(modifier.size(width, height)) {
        val s = size.width / 60f
        drawTileBack(colors, s, muted)
    }
}

/** 对手手持牌背：使用真实矩形比例，避免 60×80 缩放导致变形。 */
@Composable
fun MjBackStrip(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    Canvas(modifier.size(width, height)) {
        val radius = minOf(size.width, size.height) * 0.28f
        // 牌背也做一层釉面：上亮下暗 + 内嵌细框，远看是实体牌的背面。
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    lerp(colors.tileBack, Color.White, 0.18f),
                    colors.tileBack,
                    lerp(colors.tileBack, Color.Black, 0.16f),
                ),
            ),
            cornerRadius = CornerRadius(radius),
        )
        drawRoundRect(
            color = colors.tileBackLine,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 1f * density.coerceAtLeast(0.5f)),
        )
        drawRoundRect(
            color = colors.tileBackLine.copy(alpha = 0.42f),
            topLeft = Offset(size.width * 0.20f, size.height * 0.16f),
            size = Size(size.width * 0.60f, size.height * 0.68f),
            cornerRadius = CornerRadius(radius * 0.5f),
            style = Stroke(width = 1f),
        )
    }
}

private fun DrawScope.drawTileFace(colors: MjColors, visual: TileVisual, dimmed: Boolean, s: Float) {
    val face = colors.tileFace
    // 象牙质感：顶部提亮、底部略收暖灰，形成实体牌的厚度。
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                lerp(face, Color.White, if (colors.isDark) 0.12f else 0.35f),
                face,
                lerp(face, colors.tileEdge, 0.28f),
            ),
            startY = 1f * s,
            endY = 79f * s,
        ),
        topLeft = Offset(1f * s, 1f * s),
        size = Size(58f * s, 78f * s),
        cornerRadius = CornerRadius(8f * s),
    )
    // 顶部釉光
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.42f),
            1f to Color.White.copy(alpha = 0f),
        ),
        topLeft = Offset(3f * s, 2.5f * s),
        size = Size(54f * s, 17f * s),
        cornerRadius = CornerRadius(5f * s),
    )
    drawRoundRect(
        color = colors.tileEdge,
        topLeft = Offset(1f * s, 1f * s),
        size = Size(58f * s, 78f * s),
        cornerRadius = CornerRadius(8f * s),
        style = Stroke(width = 1.4f * s),
    )
    drawRoundRect(
        color = colors.tileInner.copy(alpha = 0.7f),
        topLeft = Offset(5.5f * s, 5.5f * s),
        size = Size(49f * s, 69f * s),
        cornerRadius = CornerRadius(4.5f * s),
        style = Stroke(width = 1f * s),
    )

    when (visual) {
        TileVisual.SELECTED -> drawRoundRect(
            color = colors.primary,
            topLeft = Offset(1.6f * s, 1.6f * s),
            size = Size(56.8f * s, 76.8f * s),
            cornerRadius = CornerRadius(7.6f * s),
            style = Stroke(width = 1.5f * s),
        )

        TileVisual.JUST_PLAYED -> drawRoundRect(
            color = colors.tableGold,
            topLeft = Offset(3f * s, 3f * s),
            size = Size(54f * s, 74f * s),
            cornerRadius = CornerRadius(6.5f * s),
            style = Stroke(width = 1.2f * s),
        )

        TileVisual.WINNABLE -> {
            drawLine(
                color = colors.tableGold,
                start = Offset(11f * s, 73.5f * s),
                end = Offset(49f * s, 73.5f * s),
                strokeWidth = 2f * s,
            )
            drawLine(
                color = colors.green,
                start = Offset(11f * s, 76.5f * s),
                end = Offset(49f * s, 76.5f * s),
                strokeWidth = 0.9f * s,
            )
        }

        TileVisual.RECOMMENDED -> {
            drawCircle(
                color = colors.tableGold,
                radius = 3.4f * s,
                center = Offset(30f * s, 70f * s),
            )
        }

        TileVisual.NORMAL -> Unit
    }

    if (dimmed) {
        drawRoundRect(
            color = colors.bg.copy(alpha = 0.45f),
            topLeft = Offset(1f * s, 1f * s),
            size = Size(58f * s, 78f * s),
            cornerRadius = CornerRadius(8f * s),
        )
    }
}

private fun DrawScope.drawTileBack(colors: MjColors, s: Float, muted: Boolean = false) {
    val alpha = if (muted) 0.72f else 1f
    drawRoundRect(
        color = colors.tileBack.copy(alpha = alpha),
        topLeft = Offset(1f * s, 1f * s),
        size = Size(58f * s, 78f * s),
        cornerRadius = CornerRadius(8f * s),
    )
    drawRoundRect(
        color = colors.tileBackLine.copy(alpha = alpha),
        topLeft = Offset(1f * s, 1f * s),
        size = Size(58f * s, 78f * s),
        cornerRadius = CornerRadius(8f * s),
        style = Stroke(width = 1.4f * s),
    )
    drawRoundRect(
        color = colors.tileBackLine.copy(alpha = 0.55f * alpha),
        topLeft = Offset(7f * s, 7f * s),
        size = Size(46f * s, 66f * s),
        cornerRadius = CornerRadius(5f * s),
        style = Stroke(width = 1f * s),
    )
}

// ------------------------------------------------------------ 牌面纹样

private val dotLayout = mapOf(
    1 to listOf(30f to 40f),
    2 to listOf(30f to 26f, 30f to 54f),
    3 to listOf(18f to 22f, 30f to 42f, 42f to 62f),
    4 to listOf(20f to 24f, 40f to 24f, 20f to 56f, 40f to 56f),
    5 to listOf(19f to 22f, 41f to 22f, 30f to 42f, 19f to 62f, 41f to 62f),
    6 to listOf(21f to 18f, 39f to 18f, 21f to 40f, 39f to 40f, 21f to 62f, 39f to 62f),
    // 七筒：三个圈自右上向左下斜排（不打横），其余四点在下部 2×2。
    7 to listOf(41f to 16f, 30f to 28f, 19f to 40f, 21f to 55f, 39f to 55f, 21f to 69f, 39f to 69f),
    8 to listOf(21f to 16f, 39f to 16f, 21f to 32f, 39f to 32f, 21f to 48f, 39f to 48f, 21f to 64f, 39f to 64f),
    9 to listOf(15f to 18f, 30f to 18f, 45f to 18f, 15f to 40f, 30f to 40f, 45f to 40f, 15f to 62f, 30f to 62f, 45f to 62f),
)

private val stickLayout = mapOf(
    2 to listOf(30f to 24f, 30f to 56f),
    3 to listOf(18f to 22f, 30f to 42f, 42f to 62f),
    4 to listOf(20f to 26f, 40f to 26f, 20f to 56f, 40f to 56f),
    5 to listOf(19f to 22f, 41f to 22f, 30f to 42f, 19f to 62f, 41f to 62f),
    6 to listOf(20f to 20f, 40f to 20f, 20f to 41f, 40f to 41f, 20f to 62f, 40f to 62f),
    // 七条：上方中间 1 根，下方两排各 3 根。
    7 to listOf(30f to 18f, 18f to 40f, 30f to 40f, 42f to 40f, 18f to 60f, 30f to 60f, 42f to 60f),
    8 to listOf(21f to 15f, 39f to 15f, 21f to 32f, 39f to 32f, 21f to 49f, 39f to 49f, 21f to 66f, 39f to 66f),
    9 to listOf(18f to 18f, 30f to 18f, 42f to 18f, 18f to 40f, 30f to 40f, 42f to 40f, 18f to 62f, 30f to 62f, 42f to 62f),
)

private val cnRank = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")

private fun DrawScope.drawGlyph(tile: Int, s: Float, tm: TextMeasurer, colors: MjColors) {
    when {
        tile in 0..8 -> {
            baselineText(tm, cnRank[Tiles.rank(tile)], 30f * s, 50f * s, 32f * s, colors.tileRed, FontWeight.SemiBold)
            baselineText(tm, "萬", 30f * s, 71f * s, 18f * s, colors.tileInk, FontWeight.SemiBold)
        }

        tile in 9..17 -> drawDots(Tiles.rank(tile), s, colors)

        tile in 18..26 -> drawSticks(Tiles.rank(tile), s, colors)

        tile in 27..30 -> {
            val text = when (tile) {
                27 -> "東"
                28 -> "南"
                29 -> "西"
                else -> "北"
            }
            baselineText(tm, text, 30f * s, 54f * s, 31f * s, colors.tileInk, FontWeight.SemiBold)
        }

        tile == 31 -> baselineText(tm, "中", 30f * s, 54f * s, 31f * s, colors.tileRed, FontWeight.SemiBold)
        tile == 32 -> baselineText(tm, "發", 30f * s, 54f * s, 31f * s, colors.tileGreen, FontWeight.SemiBold)
        tile == 33 -> {
            drawRoundRect(
                color = colors.tileBlue,
                topLeft = Offset(15f * s, 21f * s),
                size = Size(30f * s, 38f * s),
                cornerRadius = CornerRadius(3f * s),
                style = Stroke(width = 3f * s),
            )
            drawRoundRect(
                color = colors.tileBlue.copy(alpha = 0.6f),
                topLeft = Offset(21f * s, 27f * s),
                size = Size(18f * s, 26f * s),
                cornerRadius = CornerRadius(2f * s),
                style = Stroke(width = 1.1f * s),
            )
        }

        tile in 34..41 -> {
            val text = when (tile) {
                34 -> "春"
                35 -> "夏"
                36 -> "秋"
                37 -> "冬"
                38 -> "梅"
                39 -> "兰"
                40 -> "竹"
                else -> "菊"
            }
            baselineText(tm, text, 30f * s, 50f * s, 27f * s, colors.tileGreen, FontWeight.SemiBold)
            baselineText(tm, "花", 30f * s, 70f * s, 12f * s, colors.tileInk.copy(alpha = 0.55f), FontWeight.Medium)
        }
    }
}

private fun DrawScope.drawDots(rank: Int, s: Float, colors: MjColors) {
    // 传统筒子配色：墨绿为主，红点按固定规律点睛；二、四、八筒全绿。
    val ink = lerp(colors.tileGreen, Color.Black, 0.18f)
    if (rank == 1) {
        drawCircle(ink, radius = 15f * s, center = Offset(30f * s, 40f * s), style = Stroke(3f * s))
        drawCircle(ink, radius = 11.2f * s, center = Offset(30f * s, 40f * s))
        drawCircle(colors.tileFace, radius = 7.4f * s, center = Offset(30f * s, 40f * s))
        drawCircle(colors.tileRed, radius = 4.6f * s, center = Offset(30f * s, 40f * s))
        drawCircle(colors.tileFace, radius = 1.3f * s, center = Offset(30f * s, 40f * s))
        return
    }
    // 点数越少圆点越大，同一张牌内半径、留白一致。
    val r = (when {
        rank <= 4 -> 8.2f
        rank <= 6 -> 7.0f
        else -> 5.8f
    }) * s
    val positions = dotLayout[rank] ?: return
    positions.forEachIndexed { index, (x, y) ->
        val red = when (rank) {
            3 -> index == 1                    // 三筒：中间一点红
            5 -> index == 2                    // 五筒：中心红
            6 -> index >= 2                    // 六筒：下四红
            7 -> index >= 3                    // 七筒：斜排三点墨绿，下方四红
            9 -> index in 3..5                 // 九筒：中排红
            else -> false
        }
        val color = if (red) colors.tileRed else ink
        val center = Offset(x * s, y * s)
        drawCircle(color, radius = r, center = center)
        drawCircle(colors.tileFace, radius = r * 0.56f, center = center)
        drawCircle(color, radius = r * 0.30f, center = center)
    }
}

private fun DrawScope.drawSticks(rank: Int, s: Float, colors: MjColors) {
    // 传统条子配色：绿色为主，五条中段、七条顶根、九条中列用红。
    val green = colors.tileGreen
    if (rank == 1) {
        // 一条：雀鸟。绿身、深绿翅、红喙红眼，红绿比例与其余条子一致。
        val deep = lerp(green, Color.Black, 0.24f)
        // 尾羽：自尾根向下散成两片，给鸟一个明确的轮廓收尾。
        drawPath(
            Path().apply {
                moveTo(29f * s, 45f * s)
                lineTo(19.5f * s, 66f * s)
                lineTo(24f * s, 66.5f * s)
                lineTo(30f * s, 52f * s)
                lineTo(34.5f * s, 66f * s)
                lineTo(38f * s, 64.5f * s)
                lineTo(32f * s, 45f * s)
                close()
            },
            green,
        )
        // 身体：上窄下宽的蛋形，与头部圆衔接。
        drawPath(
            Path().apply {
                moveTo(32f * s, 18f * s)
                cubicTo(38f * s, 21f * s, 40f * s, 28f * s, 39.5f * s, 35f * s)
                cubicTo(39f * s, 42f * s, 35.5f * s, 46.5f * s, 30.5f * s, 50f * s)
                cubicTo(25f * s, 46f * s, 21.5f * s, 41f * s, 21f * s, 34f * s)
                cubicTo(20.5f * s, 27f * s, 26f * s, 18.5f * s, 32f * s, 18f * s)
                close()
            },
            green,
        )
        // 翅膀：一片深绿，压出身体层次。
        drawPath(
            Path().apply {
                moveTo(28f * s, 27f * s)
                cubicTo(33.5f * s, 28.5f * s, 35f * s, 34.5f * s, 30.5f * s, 40f * s)
                cubicTo(25.5f * s, 37f * s, 24.5f * s, 31f * s, 28f * s, 27f * s)
                close()
            },
            deep,
        )
        // 头、喙、眼
        drawCircle(green, radius = 5.2f * s, center = Offset(32f * s, 19f * s))
        drawPath(
            Path().apply {
                moveTo(36.6f * s, 17f * s)
                lineTo(43.4f * s, 19.5f * s)
                lineTo(36.6f * s, 21.6f * s)
                close()
            },
            colors.tileRed,
        )
        drawCircle(colors.tileRed, radius = 1.5f * s, center = Offset(33.6f * s, 18.2f * s))
        return
    }
    // 线条粗细、长度、留白统一，不再按点数改变笔触。
    val height = 12f * s
    val halfWidth = 2.5f * s
    val positions = stickLayout[rank] ?: return
    positions.forEachIndexed { index, (x, y) ->
        val red = when (rank) {
            5 -> index == 2       // 五条：中段红
            7 -> index == 0       // 七条：上方那根红
            9 -> index % 3 == 1   // 九条：中列红
            else -> false
        }
        val color = if (red) colors.tileRed else green
        drawRoundRect(
            color = color,
            topLeft = Offset(x * s - halfWidth, y * s - height / 2f),
            size = Size(halfWidth * 2f, height),
            cornerRadius = CornerRadius(halfWidth),
        )
        drawLine(
            color = colors.tileFace.copy(alpha = 0.72f),
            start = Offset((x - 1.9f) * s, y * s - 1.7f * s),
            end = Offset((x + 1.9f) * s, y * s - 1.7f * s),
            strokeWidth = 0.8f * s,
        )
        drawLine(
            color = colors.tileFace.copy(alpha = 0.72f),
            start = Offset((x - 1.9f) * s, y * s + 1.7f * s),
            end = Offset((x + 1.9f) * s, y * s + 1.7f * s),
            strokeWidth = 0.8f * s,
        )
    }
}

private fun DrawScope.baselineText(
    tm: TextMeasurer,
    text: String,
    cx: Float,
    baseline: Float,
    fontSizePx: Float,
    color: Color,
    weight: FontWeight,
) {
    val style = TextStyle(
        color = color,
        fontSize = fontSizePx.toSp(),
        fontWeight = weight,
    )
    val layout = tm.measure(AnnotatedString(text), style, softWrap = false)
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, baseline - layout.firstBaseline))
}

/** 牌桌/结算里使用的尺寸令牌。 */
object TileDims {
    val hand = 25.dp
    val handTight = 22.dp
    val drawn = 30.dp
    val river = 26.dp
    val meld = 22.dp
    val settle = 23.dp
    val riverRadius = MjRadius.small
}
