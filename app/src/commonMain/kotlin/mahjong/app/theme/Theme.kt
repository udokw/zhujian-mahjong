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
 * 牌桌主题：经典 + 八款材质皮肤全部保留，名称与 [themeSpecs] 一一对应。
 * 经典是最早的默认桌面；旧 id（竹间 / 翡翠 / 竹青 / 墨玉 / 檀木等）由
 * [mahjong.app.settings.TableSkinSerializer] 回落经典。
 */
enum class TableSkin(val label: String) {
    CLASSIC("经典"),
    WOOD("暖木"),
    QINGCI("青瓷"),
    NAVY("藏蓝"),
    VERMILION("朱砂"),
    MIST("雾灰"),
    TURQUOISE("松石"),
    LATTE("奶咖"),
    NEON("霓虹"),
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
    // 台面工艺令牌：中央盘、木框、HUD/手牌带压底、席位面板。
    val tableCore: Color,
    val coreInk: Color,
    val coreInk2: Color,
    val tableRim: Color,
    /** 无木框主题的实体桌边（同族深浅色，如青瓷月白、松石石绿）。 */
    val tableEdge: Color,
    val tableRimGlow: Color,
    val tableHud: Color,
    val hudInk: Color,
    val hudInk2: Color,
    val bandShade: Color,
    // 织纹与台面工艺参数（间距/线宽/圆角）和两路线条颜色，全部来自主题表。
    val material: TableMaterial,
    val feltLine1: Color,
    val feltLine2: Color,
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

val LocalMjColors = staticCompositionLocalOf { mjColors(false, TableSkin.CLASSIC) }
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
