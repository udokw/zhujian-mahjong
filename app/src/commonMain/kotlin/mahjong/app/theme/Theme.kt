package mahjong.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    DAY("日间"),
    NIGHT("夜间"),
}

enum class TableSkin(val label: String) {
    CLASSIC("经典"),
    BAMBOO("竹青"),
    INK("墨玉"),
}

@Immutable
data class MjColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val disabled: Color,
    val primary: Color,
    val primaryDeep: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val green: Color,
    val greenSoft: Color,
    val red: Color,
    val redSoft: Color,
    val gold: Color,
    val goldSoft: Color,
    val table1: Color,
    val table2: Color,
    val table3: Color,
    val onTable: Color,
    val onTable2: Color,
    val tableLine: Color,
    val tableGold: Color,
    val tableGoldInk: Color,
    val tileFace: Color,
    val tileEdge: Color,
    val tileInner: Color,
    val tileInk: Color,
    val tileRed: Color,
    val tileBlue: Color,
    val tileGreen: Color,
    val tileBack: Color,
    val tileBackLine: Color,
)

private fun c(hex: Long): Color = Color(0xFF000000L or hex)

private val dayBase = MjColors(
    isDark = false,
    bg = c(0xF5F7FA), surface = c(0xFFFFFF), surface2 = c(0xEDF1F7), surface3 = c(0xE4EAF3),
    line = c(0xE5E8EF), lineStrong = c(0xD4DAE4),
    ink = c(0x1D2129), ink2 = c(0x4E5969), ink3 = c(0x646E7E), disabled = c(0xA9B2C0),
    primary = c(0x2C63E0), primaryDeep = c(0x2450B8), primarySoft = c(0xEAF1FF), onPrimary = c(0xFFFFFF),
    green = c(0x0E6B4C), greenSoft = c(0xE6F5EF), red = c(0xB33636), redSoft = c(0xFCEBEB),
    gold = c(0x7E540C), goldSoft = c(0xFBF1DD),
    table1 = c(0x1B4A3D), table2 = c(0x143D33), table3 = c(0x0D2C25),
    onTable = c(0xF2F7F4), onTable2 = c(0xB9CEC4), tableLine = Color(0x24FFFFFF),
    tableGold = c(0xE8C97A), tableGoldInk = c(0x2A2110),
    tileFace = c(0xFFFFFF), tileEdge = c(0xDCE2EC), tileInner = c(0xEDF1F6),
    tileInk = c(0x1D2129), tileRed = c(0xC63C3C), tileBlue = c(0x2F6FE4), tileGreen = c(0x1B7F53),
    tileBack = c(0xE9EFF9), tileBackLine = c(0xC9D6EA),
)

private val nightBase = MjColors(
    isDark = true,
    bg = c(0x0F141B), surface = c(0x161D26), surface2 = c(0x1C2531), surface3 = c(0x243040),
    line = c(0x253040), lineStrong = c(0x33415A),
    ink = c(0xE8EDF4), ink2 = c(0xA7B3C4), ink3 = c(0x8B98AA), disabled = c(0x5E6B7D),
    primary = c(0x3D6FE0), primaryDeep = c(0x7DA4FF), primarySoft = c(0x182742), onPrimary = c(0xFFFFFF),
    green = c(0x4CC79A), greenSoft = c(0x102B24), red = c(0xEE6A6A), redSoft = c(0x38191B),
    gold = c(0xE5B863), goldSoft = c(0x2E2413),
    table1 = c(0x16443A), table2 = c(0x0E332B), table3 = c(0x082019),
    onTable = c(0xEEF5F1), onTable2 = c(0xA9C2B7), tableLine = Color(0x1FFFFFFF),
    tableGold = c(0xE8C97A), tableGoldInk = c(0x2A2110),
    tileFace = c(0xF4F1E8), tileEdge = c(0xC7C1AF), tileInner = c(0xE6E1D2),
    tileInk = c(0x1A211D), tileRed = c(0xB94A3E), tileBlue = c(0x38679A), tileGreen = c(0x2F7A5C),
    tileBack = c(0x1D2A24), tileBackLine = c(0x3E5A4C),
)

private fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)

private fun MjColors.withSkin(skin: TableSkin): MjColors {
    val t1: Color
    val t2: Color
    val t3: Color
    val gold: Color
    when (skin) {
        TableSkin.CLASSIC -> return this
        TableSkin.BAMBOO -> {
            t1 = c(0x2E4B42); t2 = c(0x213A33); t3 = c(0x142622); gold = c(0xC9D9C0)
        }
        TableSkin.INK -> {
            t1 = c(0x232A28); t2 = c(0x171D1B); t3 = c(0x0B0F0E); gold = c(0xC9A227)
        }
    }
    val shade = if (isDark) 0.12f else 0f
    return copy(
        table1 = t1.darken(shade),
        table2 = t2.darken(shade),
        table3 = t3.darken(shade),
        tableGold = gold.darken(shade),
    )
}

fun mjColors(isDark: Boolean, skin: TableSkin): MjColors =
    (if (isDark) nightBase else dayBase).withSkin(skin)

@Immutable
data class MjType(
    val display: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val num: TextStyle,
    val numLg: TextStyle,
)

private fun ts(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
)

val MjTypography = MjType(
    display = ts(28, 34, FontWeight.Bold),
    h1 = ts(22, 28, FontWeight.Bold),
    h2 = ts(17, 24, FontWeight.Bold),
    title = ts(15, 22, FontWeight.SemiBold),
    body = ts(14, 21),
    bodyStrong = ts(14, 21, FontWeight.SemiBold),
    label = ts(12, 18, FontWeight.Medium),
    caption = ts(11, 16),
    num = ts(15, 20, FontWeight.SemiBold),
    numLg = ts(28, 32, FontWeight.Bold),
)

object MjSpace {
    val page = 20.dp
    val cardGap = 12.dp
    val cardPadding = 16.dp
    val rowHeight = 46.dp
}

object MjRadius {
    val card = 24.dp
    val control = 14.dp
    val small = 12.dp
    val chip = 7.dp
}

object MjMotion {
    const val micro = 120
    const val small = 180
    const val medium = 240
    const val large = 320
}

val LocalMjColors = staticCompositionLocalOf { dayBase }
val LocalMjType = staticCompositionLocalOf { MjTypography }
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun MahjongTheme(
    mode: ThemeMode,
    skin: TableSkin,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
    }
    val colors = remember(dark, skin) { mjColors(dark, skin) }
    CompositionLocalProvider(
        LocalMjColors provides colors,
        LocalMjType provides MjTypography,
        LocalReduceMotion provides reduceMotion,
        content = content,
    )
}
