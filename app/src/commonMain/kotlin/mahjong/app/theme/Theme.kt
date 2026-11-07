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
    val wood1: Color,
    val wood2: Color,
    val woodGrain: Color,
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
    // 主主题：宣纸底 + 玉色主色 + 金色点缀，替代通用的冷蓝极简风。
    bg = c(0xF3EEE3), surface = c(0xFCFAF3), surface2 = c(0xE9E2D1), surface3 = c(0xDED4BF),
    line = c(0xDCD2BD), lineStrong = c(0xC7BAA0),
    ink = c(0x2B2519), ink2 = c(0x564D3B), ink3 = c(0x7A7159), disabled = c(0xADA48F),
    primary = c(0x1F6B52), primaryDeep = c(0x144C3A), primarySoft = c(0xDDECE2), onPrimary = c(0xFFFFFF),
    green = c(0x0E6B4C), greenSoft = c(0xE2F2E9), red = c(0xA93A32), redSoft = c(0xFAE9E5),
    gold = c(0x8A6420), goldSoft = c(0xF6E9C9),
    table1 = c(0x2E735C), table2 = c(0x22594A), table3 = c(0x174337),
    onTable = c(0xF2F7F4), onTable2 = c(0xB9CEC4), tableLine = Color(0x24FFFFFF),
    tableGold = c(0xE8C97A), tableGoldInk = c(0x2A2110),
    wood1 = c(0x7A5232), wood2 = c(0x402A1A), woodGrain = c(0x9C6F45),
    tileFace = c(0xFFFFFF), tileEdge = c(0xDCE2EC), tileInner = c(0xEDF1F6),
    tileInk = c(0x1D2129), tileRed = c(0xC63C3C), tileBlue = c(0x2F6FE4), tileGreen = c(0x1B7F53),
    tileBack = c(0xE9EFF9), tileBackLine = c(0xC9D6EA),
)

private val nightBase = MjColors(
    isDark = true,
    // 夜间主题：深檀木底 + 青玉主色，和日间共用同一套语义色位。
    bg = c(0x12100C), surface = c(0x1B1712), surface2 = c(0x241F18), surface3 = c(0x2E2820),
    line = c(0x332C22), lineStrong = c(0x463C2D),
    ink = c(0xF0E9DA), ink2 = c(0xB3A992), ink3 = c(0x948B76), disabled = c(0x615B4C),
    primary = c(0x2F8B6A), primaryDeep = c(0x7FC7A8), primarySoft = c(0x152A22), onPrimary = c(0xFFFFFF),
    green = c(0x4CC79A), greenSoft = c(0x102B24), red = c(0xEE6A6A), redSoft = c(0x38191B),
    gold = c(0xE5B863), goldSoft = c(0x2E2413),
    table1 = c(0x16443A), table2 = c(0x0E332B), table3 = c(0x082019),
    onTable = c(0xEEF5F1), onTable2 = c(0xA9C2B7), tableLine = Color(0x1FFFFFFF),
    tableGold = c(0xE8C97A), tableGoldInk = c(0x2A2110),
    wood1 = c(0x3E2C1E), wood2 = c(0x1C130D), woodGrain = c(0x5E4530),
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
