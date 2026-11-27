package mahjong.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 台面织纹类型。八款材质各用一种，经典沿用老的 10dp 细格，画法在
 * [mahjong.app.util.drawTableFelt] 里按此枚举分支：WEAVE 10dp 细格、DIAG 45° 双向斜纹、
 * CRACKLE 冰裂三向、LINEN 细亚麻、LACQUER 大漆双高光、SAND 喷砂点阵、
 * GRID 瓷砖格、RATTAN 粗藤编、SCAN 扫描线。
 */
enum class FeltPattern { WEAVE, DIAG, CRACKLE, LINEN, LACQUER, SAND, GRID, RATTAN, SCAN }

/** 无木框主题的桌沿材质：瓷（釉面渐变 + 高光带 + 开片）/ 石（颗粒 + 倒角）。 */
enum class EdgeStyle { NONE, CERAMIC, STONE }

/**
 * 台面材质参数：密度、透明度、木框、内嵌高光、牌边与圆角全部显式声明，
 * 渲染层不再按主题写 if/else，样张与真机共用同一组可核对数值。
 */
@Immutable
data class TableMaterial(
    val pattern: FeltPattern,
    /** 主间距；45° 斜纹指垂直间距。 */
    val pitch: Dp,
    /** 第二向间距（冰裂 60° / 扫描线纵线）。 */
    val pitch2: Dp = pitch,
    /** 第三向间距（冰裂 120°）。 */
    val pitch3: Dp = pitch2,
    /** 主线条透明度（日 / 夜同值）。 */
    val alpha1: Float,
    /** 次线条透明度。 */
    val alpha2: Float = alpha1,
    /** 织纹线宽。 */
    val lineWidth: Dp = 1.dp,
    /** 木框厚度；0 表示无木框，改用 [edgeWidth] 的实体桌沿。 */
    val rim: Dp,
    /** true 才画实色木框 + 内嵌高光。 */
    val rimSolid: Boolean,
    /** 无木框主题的桌沿材质与厚度（青瓷 12dp 瓷沿、松石 10dp 石沿）。 */
    val edgeStyle: EdgeStyle = EdgeStyle.NONE,
    val edgeWidth: Dp = 0.dp,
    /** 内嵌 1dp 线（ARGB），深色桌面用白。 */
    val inner: Long,
    /** 浅色桌面用的内嵌线（深墨）。 */
    val innerLight: Long = inner,
    /** 木框内侧第二道饰线（ARGB），经典用金线；null 表示不画。 */
    val accent: Long? = null,
    /** 中央盘圆角；0 表示正圆。 */
    val discRadius: Dp,
    /** 八角盘（松石）。 */
    val discOctagon: Boolean = false,
    /** 席位面板圆角。 */
    val seatRadius: Dp = 10.dp,
    /** 牌面外框描边（60×80 视图单位）。 */
    val tileEdge: Dp,
    /** 牌面圆角（60×80 视图单位）。 */
    val tileCorner: Dp,
)

/** 一款主题在某一模式下的桌面令牌，值取自 docs/design/table-themes.html。 */
@Immutable
data class SkinPalette(
    val t1: Long,
    val t2: Long,
    val t3: Long,
    val core: Long,
    val rim: Long,
    val hud: Long,
    val hudInk: Long,
    val hudInk2: Long,
    val onTable: Long,
    val onTable2: Long,
    /** 浅色桌面且中央盘为深色时的盘内文字（奶咖日）。 */
    val coreInk: Long? = null,
    val coreInk2: Long? = null,
    /** 无木框主题的实体桌边色（同族深浅色）；null 表示没有桌边。 */
    val edge: Long? = null,
    /** 桌沿内侧色：瓷沿为月白、石沿为深石绿，与 [edge] 组成厚度渐变。 */
    val edge2: Long? = null,
    val panel: Long? = null,
    val band: Long? = null,
    val line: Long? = null,
)

/** 页面底色 / 卡片 / 按钮的全局基色：皮肤只换牌桌，不换页面。 */
@Immutable
private data class PageSeed(
    val paper: Long,
    val ink: Long,
    val primary: Long,
    val gold: Long,
)

/** 每款共用、只分日夜的牌桌外壳令牌。 */
@Immutable
private data class BaseTokens(
    val gold: Long,
    val goldInk: Long,
    val panel: Long,
    val hudBtn: Long,
    val band: Long,
    val line: Long,
    val tileBack: Long,
    val tileBackLine: Long,
)

/** 每款共用、只分日夜的牌面令牌。 */
@Immutable
private data class TileTokens(
    val face: Long,
    val edge: Long,
    val inner: Long,
    val red: Long,
    val blue: Long,
    val green: Long,
    val ink: Long,
)

/** 一款主题：名称、材质、日夜两套桌面令牌。 */
data class ThemeSpec(
    val id: TableSkin,
    val name: String,
    val tagline: String,
    val material: TableMaterial,
    val day: SkinPalette,
    val night: SkinPalette,
)

private val PAGE_DAY = PageSeed(0xF3EEE3L, 0x2B2519L, 0x1F6B52L, 0x8A6420L)
private val PAGE_NIGHT = PageSeed(0x12100CL, 0xF0E9DAL, 0x39A17CL, 0xE5B863L)

private val BASE_DAY = BaseTokens(
    gold = 0xFFE8C97AL, goldInk = 0xFF2A2110L,
    panel = 0x14FFFFFFL, hudBtn = 0x1AFFFFFFL, band = 0x8C08120AL, line = 0x26FFFFFFL,
    tileBack = 0xFFE9EFF9L, tileBackLine = 0xFFC9D6EAL,
)
private val BASE_NIGHT = BaseTokens(
    gold = 0xFFE8C97AL, goldInk = 0xFF2A2110L,
    panel = 0x0FFFFFFFL, hudBtn = 0x17FFFFFFL, band = 0x94000000L, line = 0x1FFFFFFFL,
    tileBack = 0xFF1D2A24L, tileBackLine = 0xFF3E5A4CL,
)
/** 浅色桌面的外壳覆盖（青瓷 / 雾灰 / 奶咖日）。 */
private const val LIGHT_PANEL = 0x66FFFFFFL
private const val LIGHT_HUD_BTN = 0x1FFFFFFFL
private const val LIGHT_BAND = 0x8CFFFFFFL
private const val LIGHT_LINE = 0x330C1612L

private val TILE_DAY = TileTokens(
    face = 0xFFFFFFFFL, edge = 0xFFDCE2ECL, inner = 0xFFEDF1F6L,
    red = 0xFFC63C3CL, blue = 0xFF2F6FE4L, green = 0xFF1B7F53L, ink = 0xFF1D2129L,
)
private val TILE_NIGHT = TileTokens(
    face = 0xFFF4F1E8L, edge = 0xFFC7C1AFL, inner = 0xFFE6E1D2L,
    red = 0xFFB94A3EL, blue = 0xFF38679AL, green = 0xFF2F7A5CL, ink = 0xFF1A211DL,
)

// ------------------------------------------------------------ 九套材质

/**
 * 经典（原「竹间」基线）：深松绿毡 + 10dp 双向细格 + 12dp 胡桃木沿，
 * 木框内 1.4dp 金线 + 内缩 3dp 的 1dp 白线，保留最早那套桌面。
 */
private val WEAVE_MATERIAL = TableMaterial(
    pattern = FeltPattern.WEAVE, pitch = 10.dp, alpha1 = 0.05f, alpha2 = 0.05f,
    lineWidth = 1.dp, rim = 12.dp, rimSolid = true,
    inner = 0x24FFFFFFL, accent = 0x4DE8C97AL,
    discRadius = 0.dp, seatRadius = 10.dp, tileEdge = 1.4.dp, tileCorner = 8.3.dp,
)

/** 暖木：45° 双向 1dp 斜纹、4dp 间距、9dp 胡桃木框、1dp 白 16% 内嵌高光。 */
private val DIAG_MATERIAL = TableMaterial(
    pattern = FeltPattern.DIAG, pitch = 4.dp, alpha1 = 0.05f, alpha2 = 0.05f,
    lineWidth = 1.dp, rim = 9.dp, rimSolid = true, inner = 0x29FFFFFFL,
    discRadius = 12.dp, seatRadius = 10.dp, tileEdge = 1.4.dp, tileCorner = 8.3.dp,
)

/** 青瓷：冰裂开片 0°/60°/120°，间距 9/13/17dp；12dp 瓷质桌沿（奶油白→月白釉面）。 */
private val CRACKLE_MATERIAL = TableMaterial(
    pattern = FeltPattern.CRACKLE, pitch = 9.dp, pitch2 = 13.dp, pitch3 = 17.dp,
    alpha1 = 0.05f, alpha2 = 0.05f, lineWidth = 1.dp,
    rim = 0.dp, rimSolid = false, edgeStyle = EdgeStyle.CERAMIC, edgeWidth = 12.dp,
    inner = 0x3DFFFFFFL, innerLight = 0x380F1F19L,
    discRadius = 0.dp, seatRadius = 10.dp, tileEdge = 1.0.dp, tileCorner = 6.7.dp,
)

/** 藏蓝：细亚麻 5dp 双经纬（0° 4% / 90° 3%），6dp 深蓝框 + 1dp 白 20% 内高光。 */
private val LINEN_MATERIAL = TableMaterial(
    pattern = FeltPattern.LINEN, pitch = 5.dp, alpha1 = 0.04f, alpha2 = 0.03f,
    lineWidth = 1.dp, rim = 6.dp, rimSolid = true, inner = 0x33FFFFFFL,
    discRadius = 10.dp, seatRadius = 10.dp, tileEdge = 1.2.dp, tileCorner = 6.7.dp,
)

/** 朱砂：大漆不画纹理，只留两处径向高光 16% / 6%，12dp 棕框 + 1dp 金 30% 内线。 */
private val LACQUER_MATERIAL = TableMaterial(
    pattern = FeltPattern.LACQUER, pitch = 0.dp, alpha1 = 0.16f, alpha2 = 0.06f,
    lineWidth = 0.dp, rim = 12.dp, rimSolid = true, inner = 0x4DE8C97AL,
    discRadius = 0.dp, seatRadius = 10.dp, tileEdge = 1.4.dp, tileCorner = 8.3.dp,
)

/** 雾灰：3dp 喷砂点阵（5% / 3%），4dp 石墨窄框 + 1dp 内高光（浅桌转深墨）。 */
private val SAND_MATERIAL = TableMaterial(
    pattern = FeltPattern.SAND, pitch = 3.dp, alpha1 = 0.05f, alpha2 = 0.03f,
    lineWidth = 0.6.dp, rim = 4.dp, rimSolid = true,
    inner = 0x24FFFFFFL, innerLight = 0x2E121821L,
    discRadius = 12.dp, seatRadius = 10.dp, tileEdge = 1.1.dp, tileCorner = 6.7.dp,
)

/** 松石：9dp 0/90 瓷砖格（5%），10dp 石绿石材桌沿（斜切倒角），中央盘切八角。 */
private val GRID_MATERIAL = TableMaterial(
    pattern = FeltPattern.GRID, pitch = 9.dp, alpha1 = 0.05f, alpha2 = 0.05f,
    lineWidth = 1.dp, rim = 0.dp, rimSolid = false, edgeStyle = EdgeStyle.STONE, edgeWidth = 10.dp,
    inner = 0x38FFFFFFL,
    discRadius = 0.dp, discOctagon = true, seatRadius = 10.dp,
    tileEdge = 1.2.dp, tileCorner = 5.dp,
)

/** 奶咖：8dp 0/90 双向 2dp 粗藤编（6% / 5%），7dp 咖啡木框 + 1dp 内高光（浅桌转深棕）。 */
private val RATTAN_MATERIAL = TableMaterial(
    pattern = FeltPattern.RATTAN, pitch = 8.dp, alpha1 = 0.06f, alpha2 = 0.05f,
    lineWidth = 2.dp, rim = 7.dp, rimSolid = true,
    inner = 0x2EFFFFFFL, innerLight = 0x383C2610L,
    discRadius = 0.dp, seatRadius = 10.dp, tileEdge = 1.2.dp, tileCorner = 6.7.dp,
)

/** 霓虹：0° 3dp 扫描线 6% + 90° 24dp 细纵线 4%，3dp 冷光框 + 1dp 蓝 40% 内线。 */
private val SCAN_MATERIAL = TableMaterial(
    pattern = FeltPattern.SCAN, pitch = 3.dp, pitch2 = 24.dp, alpha1 = 0.06f, alpha2 = 0.04f,
    lineWidth = 1.dp, rim = 3.dp, rimSolid = true, inner = 0x6682B4FFL,
    discRadius = 16.dp, seatRadius = 10.dp, tileEdge = 1.6.dp, tileCorner = 8.3.dp,
)

// ------------------------------------------------------------ 九款主题

/**
 * 九款主题：经典（默认）+ 八款材质皮肤，顺序沿用规格表，设置页按此每行四款。
 * 改动这里就等于同时改了 Android 与 iOS：`mjColors()` 是两端唯一的取色入口。
 */
val themeSpecs: List<ThemeSpec> = listOf(
    ThemeSpec(
        id = TableSkin.CLASSIC,
        name = "经典",
        tagline = "深松绿毡、12dp 胡桃木沿、木框内金白双线。最早的默认桌面，安静耐看。",
        material = WEAVE_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF1B4A3DL, t2 = 0xFF143D33L, t3 = 0xFF0D2C25L, core = 0xFF0D2C25L,
            rim = 0xFF7A5232L, hud = 0x4D000000L, hudInk = 0xFFF2F7F4L, hudInk2 = 0xFFB9CEC4L,
            onTable = 0xFFF2F7F4L, onTable2 = 0xFFB9CEC4L,
        ),
        night = SkinPalette(
            t1 = 0xFF16443AL, t2 = 0xFF0E332BL, t3 = 0xFF082019L, core = 0xFF082019L,
            rim = 0xFF3E2C1EL, hud = 0x4D000000L, hudInk = 0xFFEEF5F1L, hudInk2 = 0xFFA9C2B7L,
            onTable = 0xFFEEF5F1L, onTable2 = 0xFFA9C2B7L,
        ),
    ),
    ThemeSpec(
        id = TableSkin.WOOD,
        name = "暖木",
        tagline = "深胡桃木桌沿配墨绿斜纹毡，老牌馆的暖光。45° 双向斜纹是它的识别点。",
        material = DIAG_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF2E5A4AL, t2 = 0xFF244B3DL, t3 = 0xFF1B3A2FL, core = 0xFF17332AL,
            rim = 0xFF4A3122L, hud = 0x8C120C07L, hudInk = 0xFFF4F8F5L, hudInk2 = 0xFFC3D6CBL,
            onTable = 0xFFF4F8F5L, onTable2 = 0xFFBCD3C7L,
        ),
        night = SkinPalette(
            t1 = 0xFF1D4335L, t2 = 0xFF16372CL, t3 = 0xFF0E2820L, core = 0xFF0A211AL,
            rim = 0xFF2F2015L, hud = 0xA8080503L, hudInk = 0xFFEDF4EFL, hudInk2 = 0xFFA9C2B5L,
            onTable = 0xFFEDF4EFL, onTable2 = 0xFFA9C2B5L,
        ),
    ),
    ThemeSpec(
        id = TableSkin.QINGCI,
        name = "青瓷",
        tagline = "月白开片瓷面，12dp 奶油白到月白的釉面桌沿，一道高光带。冰裂纹三向 9/13/17dp。",
        material = CRACKLE_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF6E9C8BL, t2 = 0xFF5A8879L, t3 = 0xFF48705FL, core = 0xFF7FA394L,
            rim = 0xFF587F70L, hud = 0x9E060E0CL, hudInk = 0xFFF2F7F4L, hudInk2 = 0xFFBFD6CBL,
            onTable = 0xFF0F1F19L, onTable2 = 0xFF223A32L,
            edge = 0xFFF4EBD9L, edge2 = 0xFFD6E4DBL,
            panel = LIGHT_PANEL, band = LIGHT_BAND, line = LIGHT_LINE,
        ),
        night = SkinPalette(
            t1 = 0xFF4A7164L, t2 = 0xFF3D6256L, t3 = 0xFF2E4E44L, core = 0xFF54796BL,
            rim = 0xFF2A463DL, hud = 0xAD030807L, hudInk = 0xFFE4EEE8L, hudInk2 = 0xFFA9C6BAL,
            onTable = 0xFFE4EEE8L, onTable2 = 0xFFA9C6BAL,
            edge = 0xFFE3D8C2L, edge2 = 0xFFAFC8BDL,
        ),
    ),
    ThemeSpec(
        id = TableSkin.NAVY,
        name = "藏蓝",
        tagline = "深海军蓝平纹毡，细亚麻 5dp 双经纬。6dp 深蓝框把桌面框成一个安安静静的长方形。",
        material = LINEN_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF2A4767L, t2 = 0xFF1E3759L, t3 = 0xFF162944L, core = 0xFF12233BL,
            rim = 0xFF101C2EL, hud = 0x94060A12L, hudInk = 0xFFF1F5FAL, hudInk2 = 0xFFC2D0E2L,
            onTable = 0xFFF1F5FAL, onTable2 = 0xFFC2D0E2L,
        ),
        night = SkinPalette(
            t1 = 0xFF1C3550L, t2 = 0xFF142845L, t3 = 0xFF0E1E33L, core = 0xFF0B1829L,
            rim = 0xFF0A1523L, hud = 0xB303060CL, hudInk = 0xFFE8EFF8L, hudInk2 = 0xFFA8BAD2L,
            onTable = 0xFFE8EFF8L, onTable2 = 0xFFA8BAD2L,
        ),
    ),
    ThemeSpec(
        id = TableSkin.VERMILION,
        name = "朱砂",
        tagline = "大漆朱红，只靠两处径向高光提亮，不靠纹理。12dp 深棕框 + 一圈 30% 金内线。",
        material = LACQUER_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF8E3A33L, t2 = 0xFF7A2E28L, t3 = 0xFF5E211CL, core = 0xFF4E1A16L,
            rim = 0xFF2A0F0CL, hud = 0x8F140706L, hudInk = 0xFFFBF1ECL, hudInk2 = 0xFFDCC0B8L,
            onTable = 0xFFFBF1ECL, onTable2 = 0xFFDCC0B8L,
        ),
        night = SkinPalette(
            t1 = 0xFF7A2C25L, t2 = 0xFF65231EL, t3 = 0xFF4A1815L, core = 0xFF3E1310L,
            rim = 0xFF200B09L, hud = 0xAD0C0403L, hudInk = 0xFFF7EBE6L, hudInk2 = 0xFFD2B3ABL,
            onTable = 0xFFF7EBE6L, onTable2 = 0xFFD2B3ABL,
        ),
    ),
    ThemeSpec(
        id = TableSkin.MIST,
        name = "雾灰",
        tagline = "中性灰喷砂毡，3dp 点阵细颗粒，4dp 窄框。最没有性格的一款，任何牌面都不抢戏。",
        material = SAND_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF7E8894L, t2 = 0xFF707A86L, t3 = 0xFF5C6672L, core = 0xFF8C96A2L,
            rim = 0xFF3C434CL, hud = 0xA30A0E14L, hudInk = 0xFFF2F5F9L, hudInk2 = 0xFFC3CBD6L,
            onTable = 0xFF121821L, onTable2 = 0xFF242C35L,
            panel = LIGHT_PANEL, band = LIGHT_BAND, line = LIGHT_LINE,
        ),
        night = SkinPalette(
            t1 = 0xFF5A636EL, t2 = 0xFF4E5762L, t3 = 0xFF3C444DL, core = 0xFF5E6874L,
            rim = 0xFF2A3038L, hud = 0xB305080CL, hudInk = 0xFFF0F3F7L, hudInk2 = 0xFFB9C2CEL,
            onTable = 0xFFF0F3F7L, onTable2 = 0xFFB9C2CEL,
        ),
    ),
    ThemeSpec(
        id = TableSkin.TURQUOISE,
        name = "松石",
        tagline = "松石绿瓷砖格，9dp 0/90 格子最硬朗。10dp 石绿石材桌沿，斜切倒角。",
        material = GRID_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF1E756DL, t2 = 0xFF17665FL, t3 = 0xFF114F49L, core = 0xFF0E443EL,
            rim = 0xFF0C3C37L, hud = 0x94040E0DL, hudInk = 0xFFEFF8F5L, hudInk2 = 0xFFB7D6CFL,
            onTable = 0xFFEFF8F5L, onTable2 = 0xFFB7D6CFL,
            edge = 0xFF7FBFAFL, edge2 = 0xFF2B665DL,
        ),
        night = SkinPalette(
            t1 = 0xFF186863L, t2 = 0xFF125551L, t3 = 0xFF0D423FL, core = 0xFF0B3733L,
            rim = 0xFF082C29L, hud = 0xB3020908L, hudInk = 0xFFE8F4F1L, hudInk2 = 0xFFA9C6C0L,
            onTable = 0xFFE8F4F1L, onTable2 = 0xFFA9C6C0L,
            edge = 0xFF5C9E90L, edge2 = 0xFF1B4F49L,
        ),
    ),
    ThemeSpec(
        id = TableSkin.LATTE,
        name = "奶咖",
        tagline = "奶咖棕粗藤编，8dp 双向 2dp 粗线。7dp 咖啡木框，日间暖浅桌、夜间转深棕。",
        material = RATTAN_MATERIAL,
        day = SkinPalette(
            t1 = 0xFFA0825FL, t2 = 0xFF8B6F51L, t3 = 0xFF71583EL, core = 0xFF5E4934L,
            rim = 0xFF6B4B2EL, hud = 0xA3120C06L, hudInk = 0xFFFBF6EDL, hudInk2 = 0xFFE2D2BBL,
            onTable = 0xFF1C130AL, onTable2 = 0xFF33281AL,
            coreInk = 0xFFFBF3E8L, coreInk2 = 0xBDFBF3E8L,
            panel = 0x75FFFFFFL, band = 0x9EFFFBF5L, line = 0x33281A0CL,
        ),
        night = SkinPalette(
            t1 = 0xFF7A6149L, t2 = 0xFF67523FL, t3 = 0xFF524131L, core = 0xFF46372AL,
            rim = 0xFF402C1BL, hud = 0xB3090603L, hudInk = 0xFFF6EEE2L, hudInk2 = 0xFFCDBCA6L,
            onTable = 0xFFF6EEE2L, onTable2 = 0xFFCDBCA6L,
        ),
    ),
    ThemeSpec(
        id = TableSkin.NEON,
        name = "霓虹",
        tagline = "冷调午夜蓝，3dp 扫描线 + 24dp 细密纵线，屏幕上像 CRT 微光。牌边最厚 1.6dp。",
        material = SCAN_MATERIAL,
        day = SkinPalette(
            t1 = 0xFF414C72L, t2 = 0xFF374163L, t3 = 0xFF2B3450L, core = 0xFF252B45L,
            rim = 0xFF1A2440L, hud = 0x9E070914L, hudInk = 0xFFEEF2FEL, hudInk2 = 0xFFC2CBE8L,
            onTable = 0xFFEEF2FEL, onTable2 = 0xFFC2CBE8L,
        ),
        night = SkinPalette(
            t1 = 0xFF1B2338L, t2 = 0xFF0F1729L, t3 = 0xFF0A101EL, core = 0xFF232C46L,
            rim = 0xFF17213AL, hud = 0xB804060EL, hudInk = 0xFFE9EEFBL, hudInk2 = 0xFFAAB4D4L,
            onTable = 0xFFE9EEFBL, onTable2 = 0xFFAAB4D4L,
        ),
    ),
)

fun themeSpec(skin: TableSkin): ThemeSpec =
    themeSpecs.firstOrNull { it.id == skin } ?: themeSpecs.first()

// ------------------------------------------------------------ 颜色数学

private fun c(rgb: Long): Color = Color(0xFF000000L or rgb)

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
 * 两端唯一的取色入口：页面用全局日夜基色推导，牌桌用皮肤显式令牌。
 * 所有主题定义都在 [themeSpecs]，Android / iOS 共用，不存在平台分支。
 */
fun mjColors(isDark: Boolean, skin: TableSkin): MjColors = buildColors(themeSpec(skin), isDark)

private fun buildColors(spec: ThemeSpec, isDark: Boolean): MjColors {
    val page = if (isDark) PAGE_NIGHT else PAGE_DAY
    val palette = if (isDark) spec.night else spec.day
    val base = if (isDark) BASE_NIGHT else BASE_DAY
    val tile = if (isDark) TILE_NIGHT else TILE_DAY
    val material = spec.material

    val p = page.paper
    val ink = page.ink
    val white = 0xFFFFFFL
    // 浅色桌面由桌面文字亮度判定：青瓷 / 雾灰 / 奶咖的日间。
    val lightTable = luminance(palette.onTable and white) < 0.5f
    val lineColor = if (lightTable) Color.Black else Color.White
    val innerArgb = if (lightTable) material.innerLight else material.inner

    val panel = palette.panel ?: if (lightTable) LIGHT_PANEL else base.panel
    val band = palette.band ?: if (lightTable) LIGHT_BAND else base.band
    val line = palette.line ?: if (lightTable) LIGHT_LINE else base.line
    val hudBtn = if (lightTable) LIGHT_HUD_BTN else base.hudBtn

    val seatActiveArgb = if (lightTable) 0x3DFFFFFFL else 0x24FFFFFFL

    return MjColors(
        isDark = isDark,
        skin = spec.id,
        bg = c(p),
        surface = if (isDark) c(mix(p, white, 0.052f)) else c(mix(p, white, 0.62f)),
        surface2 = if (isDark) c(mix(p, white, 0.095f)) else c(mix(p, ink, 0.05f)),
        surface3 = if (isDark) c(mix(p, white, 0.145f)) else c(mix(p, ink, 0.11f)),
        line = if (isDark) c(mix(p, white, 0.125f)) else c(mix(p, ink, 0.13f)),
        lineStrong = if (isDark) c(mix(p, white, 0.22f)) else c(mix(p, ink, 0.23f)),
        ink = c(ink),
        ink2 = c(mix(ink, p, if (isDark) 0.28f else 0.30f)),
        ink3 = c(mix(ink, p, if (isDark) 0.42f else 0.45f)),
        disabled = c(mix(ink, p, if (isDark) 0.62f else 0.58f)),
        primary = c(page.primary),
        primaryDeep = if (isDark) c(mix(page.primary, white, 0.42f)) else c(darken(page.primary, 0.28f)),
        primarySoft = c(mix(page.primary, p, if (isDark) 0.86f else 0.90f)),
        onPrimary = c(if (luminance(page.primary) > 0.18f) 0x1A1712L else white),
        green = c(if (isDark) 0x4CC79AL else 0x0E6B4CL),
        greenSoft = c(mix(if (isDark) 0x4CC79AL else 0x0E6B4CL, p, if (isDark) 0.86f else 0.90f)),
        red = c(if (isDark) 0xEE6A6AL else 0xA93A32L),
        redSoft = c(mix(if (isDark) 0xEE6A6AL else 0xA93A32L, p, if (isDark) 0.86f else 0.90f)),
        gold = c(page.gold),
        goldSoft = c(mix(page.gold, p, if (isDark) 0.86f else 0.88f)),
        table1 = c(palette.t1),
        table2 = c(palette.t2),
        table3 = c(palette.t3),
        onTable = c(palette.onTable),
        onTable2 = c(palette.onTable2),
        tableLine = Color(line),
        tableGold = Color(base.gold),
        tableGoldInk = Color(base.goldInk),
        tableCore = c(palette.core),
        coreInk = c(palette.coreInk ?: palette.onTable),
        coreInk2 = Color(palette.coreInk2 ?: palette.onTable2),
        tableRim = c(palette.rim),
        tableEdge = c(palette.edge ?: palette.rim),
        tableEdge2 = c(palette.edge2 ?: palette.edge ?: palette.rim),
        tableRimGlow = Color(innerArgb),
        tableHud = Color(palette.hud),
        hudInk = c(palette.hudInk),
        hudInk2 = c(palette.hudInk2),
        bandShade = Color(band),
        material = material,
        feltLine1 = lineColor.copy(alpha = material.alpha1),
        feltLine2 = lineColor.copy(alpha = material.alpha2),
        seatFill = Color(panel),
        seatFillActive = Color(seatActiveArgb),
        hudChipFill = Color(hudBtn),
        wood1 = c(palette.rim),
        wood2 = c(darken(palette.rim, if (isDark) 0.42f else 0.46f)),
        woodGrain = c(mix(palette.rim, white, if (isDark) 0.14f else 0.18f)),
        tileFace = c(tile.face),
        tileEdge = c(tile.edge),
        tileInner = c(tile.inner),
        tileInk = c(tile.ink),
        tileRed = c(tile.red),
        tileBlue = c(tile.blue),
        tileGreen = c(tile.green),
        tileBack = c(base.tileBack),
        tileBackLine = c(base.tileBackLine),
    )
}
