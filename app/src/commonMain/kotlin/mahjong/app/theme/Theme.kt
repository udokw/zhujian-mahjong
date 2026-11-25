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

/**
 * 牌桌主题。名称直接对应 [themeSpecs] 里的四款主题；CLASSIC / WOOD 两个旧枚举名
 * 保留下来只为兼容已保存的设置（旧「经典」= 竹间，旧「暖木」= 檀木）。
 */
enum class TableSkin(val label: String) {
    CLASSIC("竹间"),
    QINGCI("青瓷"),
    WOOD("檀木"),
    FEICUI("翡翠"),
}

@Immutable
data class MjColors(
    val isDark: Boolean,
    val skin: TableSkin = TableSkin.CLASSIC,
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
    // 台面工艺令牌（暖木）：中央盘、木框、HUD/手牌带压底、毡纹与席位面板。
    val tableCore: Color,
    val tableRim: Color,
    val tableRimGlow: Color,
    val tableHud: Color,
    val bandShade: Color,
    val feltStripe: Color,
    val seatFill: Color,
    val seatFillActive: Color,
    val hudChipFill: Color,
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
    // 经典皮肤沿用旧观感：HUD/手牌带仍是纯黑压底，席位面板继续用 table3 半透明。
    tableCore = c(0x174337), tableRim = c(0x7A5232), tableRimGlow = Color(0x38FFFFFF),
    tableHud = Color(0x4D000000), bandShade = Color(0x42000000), feltStripe = Color(0x04FFFFFF),
    seatFill = Color(0x66174337), seatFillActive = Color(0x9E174337L), hudChipFill = Color.Transparent,
    wood1 = c(0x7A5232), wood2 = c(0x402A1A), woodGrain = c(0x9C6F45),
    tileFace = c(0xFFFFFF), tileEdge = c(0xDCE2EC), tileInner = c(0xEDF1F6),
    tileInk = c(0x1D2129), tileRed = c(0xC63C3C), tileBlue = c(0x2F6FE4), tileGreen = c(0x1B7F53),
    tileBack = c(0xE9EFF9), tileBackLine = c(0xC9D6EA),
)

/**
 * 两端唯一的取色入口：先按主题取种子，再推导出整套语义色。
 * 所有主题定义都在 [themeSpecs]，Android / iOS 共用，不存在平台分支。
 */
fun mjColors(isDark: Boolean, skin: TableSkin): MjColors =
    deriveColors(themeSpec(skin), isDark)

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
