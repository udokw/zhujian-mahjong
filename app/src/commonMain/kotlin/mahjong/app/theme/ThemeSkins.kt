package mahjong.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 绒布织纹：八款主题只换织法与配色，牌桌几何完全共用。
 * 具体画法在 [mahjong.app.util.drawTableFelt] 里按此枚举分支。
 */
enum class FeltPattern { WEAVE, LACQUER, DAMASK, LINEN, BRUSHED, RATTAN, PLAIN }

/**
 * 一款主题的决定性种子色。其余三十多个语义色（页面、卡片、牌面、台面工艺令牌）
 * 都由 [deriveColors] 按同一套公式推导，保证浅色/深色两种模式都自洽。
 */
data class ThemeSeed(
    /** 页面纸色 / 夜间底色 */
    val paper: Long,
    /** 正文墨色 */
    val ink: Long,
    /** 主色（按钮、选中态） */
    val primary: Long,
    /** 金色点缀 */
    val gold: Long,
    /** 绒布中间调 */
    val felt: Long,
    /** 木沿主色 */
    val wood: Long,
    /** 牌背色 */
    val back: Long,
)

/** 一款主题的完整定义：名称、材质、日夜两套种子。 */
data class ThemeSpec(
    val id: TableSkin,
    val name: String,
    val tagline: String,
    val pattern: FeltPattern,
    val day: ThemeSeed,
    val night: ThemeSeed,
)

/**
 * 四款主题（顺序按由浅到深，设置页直接照此排列）。
 *
 * 改动这里就等于同时改了 Android 与 iOS：`mjColors()` 是两端唯一的取色入口。
 */
val themeSpecs: List<ThemeSpec> = listOf(
    ThemeSpec(
        id = TableSkin.CLASSIC,
        name = "竹间",
        tagline = "宣纸底、青玉绒、胡桃木沿。默认的日常牌桌，安静、耐看。",
        pattern = FeltPattern.WEAVE,
        day = ThemeSeed(0xF3EEE3, 0x2B2519, 0x1F6B52, 0x8A6420, 0x2E735C, 0x7A5232, 0xE9EFF9),
        night = ThemeSeed(0x12100C, 0xF0E9DA, 0x39A17C, 0xE5B863, 0x16443A, 0x3E2C1E, 0x1D2A24),
    ),
    ThemeSpec(
        id = TableSkin.QINGCI,
        name = "青瓷",
        tagline = "月白底、青瓷绒、灰木沿。像一件开片的宋瓷，凉而不冷。",
        pattern = FeltPattern.LINEN,
        day = ThemeSeed(0xEBF0EC, 0x1F2A26, 0x4C7A6B, 0x8C7B4A, 0x78998D, 0xA8A294, 0xEDF2EC),
        night = ThemeSeed(0x0E1513, 0xE4EEE8, 0x8FC7B2, 0xC9B77E, 0x2C4740, 0x33302A, 0x1E2C27),
    ),
    ThemeSpec(
        id = TableSkin.WOOD,
        name = "檀木",
        tagline = "暖棕桌面、米黄牌背。像巷口那家开了三十年的老茶馆。",
        pattern = FeltPattern.RATTAN,
        day = ThemeSeed(0xF1E7D6, 0x33261A, 0x7A5232, 0xA9791F, 0x5C4A32, 0x5A3A22, 0xF4EBDC),
        night = ThemeSeed(0x140F0A, 0xF1E6D3, 0xC08A55, 0xD8B26A, 0x33271A, 0x2C1C11, 0x261C12),
    ),
    ThemeSpec(
        id = TableSkin.FEICUI,
        name = "翡翠",
        tagline = "深翠绒面、象牙牌、黄铜高光。富贵气，但不落俗。",
        pattern = FeltPattern.WEAVE,
        day = ThemeSeed(0xF0F2E8, 0x15241B, 0x0F7048, 0xB3892A, 0x12603F, 0x4E3A26, 0xDCE7D6),
        night = ThemeSeed(0x08150F, 0xE7F2E7, 0x4CC79A, 0xD9B45C, 0x0A3A28, 0x221A11, 0x16281E),
    ),
)

fun themeSpec(skin: TableSkin): ThemeSpec =
    themeSpecs.firstOrNull { it.id == skin } ?: themeSpecs.first()

private fun c(hex: Long): Color = Color(0xFF000000L or hex)

/** 半透明覆盖色：alpha 为 0..255。 */
private fun a(alpha: Int, rgb: Long): Color =
    Color((alpha.toLong() shl 24) or (rgb and 0xFFFFFFL))

private fun mixCh(from: Long, to: Long, shift: Int, t: Float): Int {
    val f = ((from shr shift) and 0xFF).toFloat()
    val g = ((to shr shift) and 0xFF).toFloat()
    return (f + (g - f) * t).roundToInt().coerceIn(0, 255)
}

/** 线性混色：t=0 取 from，t=1 取 to。 */
private fun mix(from: Long, to: Long, t: Float): Long {
    val r = mixCh(from, to, 16, t)
    val g = mixCh(from, to, 8, t)
    val b = mixCh(from, to, 0, t)
    return (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

/** 压暗：amount=0.2 表示每个通道乘 0.8。 */
private fun darken(rgb: Long, amount: Float): Long {
    val k = 1f - amount
    val r = (((rgb shr 16) and 0xFF) * k).roundToInt().coerceIn(0, 255)
    val g = (((rgb shr 8) and 0xFF) * k).roundToInt().coerceIn(0, 255)
    val b = ((rgb and 0xFF) * k).roundToInt().coerceIn(0, 255)
    return (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

private fun luminance(rgb: Long): Float {
    fun lin(v: Float): Float {
        val s = v / 255f
        return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }
    val r = lin(((rgb shr 16) and 0xFF).toFloat())
    val g = lin(((rgb shr 8) and 0xFF).toFloat())
    val b = lin((rgb and 0xFF).toFloat())
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * 由种子推导整套语义色。日夜两套规则不同：
 * 日间是「纸 → 卡片更亮、线条更淡」，夜间是「底 → 卡片略亮、线条略亮」。
 * 与 docs/design/theme-gallery.html 里的 derive() 保持同一套数值，样张即真机。
 */
fun deriveColors(spec: ThemeSpec, isDark: Boolean): MjColors {
    val s = if (isDark) spec.night else spec.day
    val p = s.paper
    val ink = s.ink
    val felt1 = s.felt
    val felt2 = darken(felt1, if (isDark) 0.14f else 0.16f)
    val felt3 = darken(felt1, if (isDark) 0.30f else 0.33f)
    val onTableRgb = mix(0xFFFFFF, felt1, 0.06f)
    val faceRgb = if (isDark) mix(0xF6F3EA, p, 0.03f) else mix(0xFFFFFF, p, 0.05f)

    return MjColors(
        isDark = isDark,
        skin = spec.id,
        bg = c(p),
        surface = if (isDark) c(mix(p, 0xFFFFFF, 0.052f)) else c(mix(p, 0xFFFFFF, 0.62f)),
        surface2 = if (isDark) c(mix(p, 0xFFFFFF, 0.095f)) else c(mix(p, ink, 0.05f)),
        surface3 = if (isDark) c(mix(p, 0xFFFFFF, 0.145f)) else c(mix(p, ink, 0.11f)),
        line = if (isDark) c(mix(p, 0xFFFFFF, 0.125f)) else c(mix(p, ink, 0.13f)),
        lineStrong = if (isDark) c(mix(p, 0xFFFFFF, 0.22f)) else c(mix(p, ink, 0.23f)),
        ink = c(ink),
        ink2 = c(mix(ink, p, if (isDark) 0.28f else 0.30f)),
        ink3 = c(mix(ink, p, if (isDark) 0.42f else 0.45f)),
        disabled = c(mix(ink, p, if (isDark) 0.62f else 0.58f)),
        primary = c(s.primary),
        primaryDeep = if (isDark) c(mix(s.primary, 0xFFFFFF, 0.42f)) else c(darken(s.primary, 0.28f)),
        primarySoft = c(mix(s.primary, p, if (isDark) 0.86f else 0.90f)),
        // 亮色主色（夜间四款）配深字，深色主色（日间）配白字，保证按钮文字 ≥4.5。
        onPrimary = c(if (luminance(s.primary) > 0.18f) 0x1A1712 else 0xFFFFFF),
        green = c(if (isDark) 0x4CC79A else 0x0E6B4C),
        greenSoft = c(mix(if (isDark) 0x4CC79A else 0x0E6B4C, p, if (isDark) 0.86f else 0.90f)),
        red = c(if (isDark) 0xEE6A6A else 0xA93A32),
        redSoft = c(mix(if (isDark) 0xEE6A6A else 0xA93A32, p, if (isDark) 0.86f else 0.90f)),
        gold = c(s.gold),
        goldSoft = c(mix(s.gold, p, if (isDark) 0.86f else 0.88f)),
        table1 = c(felt1),
        table2 = c(felt2),
        table3 = c(felt3),
        onTable = c(onTableRgb),
        onTable2 = c(mix(onTableRgb, felt1, 0.32f)),
        tableLine = a(if (isDark) 0x1F else 0x24, 0xFFFFFF),
        // 日间金键压成浅金，深字才够 4.5；夜间金键本身够亮，保持种子原色。
        tableGold = c(if (isDark) s.gold else mix(s.gold, 0xFFFFFF, 0.62f)),
        tableGoldInk = c(0x2A2110),
        tableCore = c(felt3),
        tableRim = c(s.wood),
        tableRimGlow = a(if (isDark) 0x1F else 0x38, 0xFFFFFF),
        tableHud = a(0x4D, 0x000000),
        bandShade = a(0x42, 0x000000),
        feltStripe = a(if (isDark) 0x04 else 0x0A, 0xFFFFFF),
        seatFill = a(0x66, felt3),
        seatFillActive = a(0x9E, felt3),
        hudChipFill = Color.Transparent,
        wood1 = c(s.wood),
        wood2 = c(darken(s.wood, if (isDark) 0.42f else 0.46f)),
        woodGrain = c(mix(s.wood, 0xFFFFFF, if (isDark) 0.14f else 0.18f)),
        tileFace = c(faceRgb),
        tileEdge = if (isDark) c(mix(faceRgb, 0x000000, 0.20f)) else c(mix(faceRgb, ink, 0.14f)),
        tileInner = if (isDark) c(mix(faceRgb, 0x000000, 0.07f)) else c(mix(faceRgb, ink, 0.06f)),
        tileInk = if (isDark) c(0x1A201C) else c(darken(ink, 0.10f)),
        tileRed = c(if (isDark) 0xB4402F else 0xC63C3C),
        tileBlue = c(if (isDark) 0x2E5F92 else 0x2F6FE4),
        tileGreen = c(if (isDark) 0x23664C else 0x1B7F53),
        tileBack = c(s.back),
        tileBackLine = if (luminance(s.back) > 0.5f) c(mix(s.back, ink, 0.18f))
        else c(mix(s.back, 0xFFFFFF, 0.22f)),
    )
}
