package mahjong.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mahjong.app.theme.LocalMjColors

enum class MjIconKind {
    BACK, HOME, TABLE, RECORD, USER, GEAR, PLUS, CLOSE, CHECK,
    CHEVRON_RIGHT, CHEVRON_DOWN, ROBOT, WIFI, LOGOUT, REFRESH, COPY, MENU,
}

@Composable
fun MjIcon(
    kind: MjIconKind,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalMjColors.current.ink,
    stroke: Dp = 1.7.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        val sw = stroke.value * density
        val strokeStyle = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            MjIconKind.BACK -> path(
                listOf(15f to 5f, 8f to 12f, 15f to 19f), tint, strokeStyle, s,
            )

            MjIconKind.HOME -> {
                path(listOf(4f to 10.5f, 12f to 4f, 20f to 10.5f), tint, strokeStyle, s)
                path(listOf(6f to 9.5f, 6f to 20f, 18f to 20f, 18f to 9.5f), tint, strokeStyle, s)
                path(listOf(10f to 20f, 10f to 14.5f, 14f to 14.5f, 14f to 20f), tint, strokeStyle, s)
            }

            MjIconKind.TABLE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.5f * s, 4f * s),
                    size = Size(17f * s, 16f * s),
                    cornerRadius = CornerRadius(4f * s),
                    style = strokeStyle,
                )
                drawLine(tint, Offset(3.5f * s, 10f * s), Offset(20.5f * s, 10f * s), sw)
                drawLine(tint, Offset(10f * s, 4f * s), Offset(10f * s, 20f * s), sw)
            }

            MjIconKind.RECORD -> {
                path(listOf(6f to 3.5f, 15f to 3.5f, 18.5f to 7f, 18.5f to 20.5f, 6f to 20.5f, 6f to 3.5f), tint, strokeStyle, s)
                path(listOf(9f to 9f, 15f to 9f), tint, strokeStyle, s)
                path(listOf(9f to 13f, 15f to 13f), tint, strokeStyle, s)
                path(listOf(9f to 17f, 13f to 17f), tint, strokeStyle, s)
            }

            MjIconKind.USER -> {
                drawCircle(tint, radius = 3.4f * s, center = Offset(12f * s, 8f * s), style = strokeStyle)
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(4.8f * s, 12.4f * s),
                    size = Size(14.4f * s, 12f * s),
                    style = strokeStyle,
                )
            }

            MjIconKind.GEAR -> {
                drawCircle(tint, radius = 3.2f * s, center = Offset(12f * s, 12f * s), style = strokeStyle)
                drawCircle(tint, radius = 7.4f * s, center = Offset(12f * s, 12f * s), style = strokeStyle)
                for (i in 0 until 8) {
                    val angle = (i * 45) * kotlin.math.PI.toFloat() / 180f
                    val dx = kotlin.math.cos(angle)
                    val dy = kotlin.math.sin(angle)
                    drawLine(
                        color = tint,
                        start = Offset(12f * s + dx * 8.6f * s, 12f * s + dy * 8.6f * s),
                        end = Offset(12f * s + dx * 10.4f * s, 12f * s + dy * 10.4f * s),
                        strokeWidth = sw,
                    )
                }
            }

            MjIconKind.PLUS -> {
                drawLine(tint, Offset(12f * s, 5f * s), Offset(12f * s, 19f * s), sw)
                drawLine(tint, Offset(5f * s, 12f * s), Offset(19f * s, 12f * s), sw)
            }

            MjIconKind.CLOSE -> {
                drawLine(tint, Offset(6f * s, 6f * s), Offset(18f * s, 18f * s), sw)
                drawLine(tint, Offset(18f * s, 6f * s), Offset(6f * s, 18f * s), sw)
            }

            MjIconKind.CHECK -> path(listOf(5f to 12.5f, 10f to 17.5f, 19f to 6.5f), tint, strokeStyle, s)

            MjIconKind.CHEVRON_RIGHT -> path(listOf(9f to 5f, 16f to 12f, 9f to 19f), tint, strokeStyle, s)

            MjIconKind.CHEVRON_DOWN -> path(listOf(5f to 9f, 12f to 16f, 19f to 9f), tint, strokeStyle, s)

            MjIconKind.ROBOT -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(4f * s, 6.5f * s),
                    size = Size(16f * s, 13f * s),
                    cornerRadius = CornerRadius(4f * s),
                    style = strokeStyle,
                )
                drawLine(tint, Offset(12f * s, 6.5f * s), Offset(12f * s, 3.5f * s), sw)
                drawCircle(tint, radius = 1.1f * s, center = Offset(12f * s, 2.8f * s))
                drawCircle(tint, radius = 1.3f * s, center = Offset(9f * s, 12f * s))
                drawCircle(tint, radius = 1.3f * s, center = Offset(15f * s, 12f * s))
                drawLine(tint, Offset(9f * s, 16.2f * s), Offset(15f * s, 16.2f * s), sw)
            }

            MjIconKind.WIFI -> {
                drawArc(tint, 205f, 130f, false, Offset(3f * s, 6f * s), Size(18f * s, 14f * s), style = strokeStyle)
                drawArc(tint, 215f, 110f, false, Offset(6.4f * s, 10f * s), Size(11.2f * s, 9f * s), style = strokeStyle)
                drawCircle(tint, radius = 1.2f * s, center = Offset(12f * s, 18.5f * s))
            }

            MjIconKind.LOGOUT -> {
                path(listOf(14f to 4f, 5f to 4f, 5f to 20f, 14f to 20f), tint, strokeStyle, s)
                path(listOf(10f to 12f, 20f to 12f), tint, strokeStyle, s)
                path(listOf(17f to 8.5f, 20.5f to 12f, 17f to 15.5f), tint, strokeStyle, s)
            }

            MjIconKind.REFRESH -> {
                drawArc(tint, 40f, 290f, false, Offset(4f * s, 4f * s), Size(16f * s, 16f * s), style = strokeStyle)
                path(listOf(16.5f to 3.5f, 20f to 7.5f, 15.5f to 9.5f), tint, strokeStyle, s)
            }

            MjIconKind.COPY -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(8f * s, 8f * s),
                    size = Size(11.5f * s, 11.5f * s),
                    cornerRadius = CornerRadius(2.5f * s),
                    style = strokeStyle,
                )
                path(listOf(15f to 4.5f, 4.5f to 4.5f, 4.5f to 15f), tint, strokeStyle, s)
            }

            MjIconKind.MENU -> {
                drawLine(tint, Offset(4f * s, 7f * s), Offset(20f * s, 7f * s), sw)
                drawLine(tint, Offset(4f * s, 12f * s), Offset(20f * s, 12f * s), sw)
                drawLine(tint, Offset(4f * s, 17f * s), Offset(20f * s, 17f * s), sw)
            }
        }
    }
}

private fun DrawScope.path(points: List<Pair<Float, Float>>, color: Color, style: Stroke, s: Float) {
    val p = Path()
    points.forEachIndexed { index, (x, y) ->
        if (index == 0) p.moveTo(x * s, y * s) else p.lineTo(x * s, y * s)
    }
    drawPath(p, color, style = style)
}
