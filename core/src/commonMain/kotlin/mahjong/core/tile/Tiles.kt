package mahjong.core.tile

/**
 * 牌张使用 0..41 的整数编码，方便计数、序列化与跨平台复现。
 *
 * 0..8   万 1-9
 * 9..17  筒 1-9
 * 18..26 条 1-9
 * 27..30 东南西北
 * 31..33 中发白
 * 34..41 春夏秋冬梅兰竹菊（花牌，不参与和牌牌型）
 */
object Tiles {
    const val TYPE_COUNT = 42
    const val FLOWER_START = 34
    const val FLOWER_COUNT = 8

    const val EAST = 27
    const val SOUTH = 28
    const val WEST = 29
    const val NORTH = 30
    const val RED = 31
    const val GREEN = 32
    const val WHITE = 33

    val numberTiles: IntRange = 0..26
    val honorTiles: IntRange = 27..33
    val flowerTiles: IntRange = 34..41
    val normalTiles: IntRange = 0..33

    fun isFlower(tile: Int): Boolean = tile in FLOWER_START until TYPE_COUNT

    fun isHonor(tile: Int): Boolean = tile in honorTiles

    fun isWind(tile: Int): Boolean = tile in 27..30

    fun isDragon(tile: Int): Boolean = tile in 31..33

    /** 0=万, 1=筒, 2=条, 3=字 */
    fun suit(tile: Int): Int = when {
        tile in 0..8 -> 0
        tile in 9..17 -> 1
        tile in 18..26 -> 2
        else -> 3
    }

    /** 数牌返回 1..9；字牌返回 1..7（东南西北中发白）。 */
    fun rank(tile: Int): Int = when {
        tile in 0..26 -> tile % 9 + 1
        else -> tile - 26
    }

    fun isTerminal(tile: Int): Boolean = !isHonor(tile) && !isFlower(tile) && (rank(tile) == 1 || rank(tile) == 9)

    fun isTerminalOrHonor(tile: Int): Boolean = isHonor(tile) || isTerminal(tile)

    fun isSimple(tile: Int): Boolean = !isHonor(tile) && !isFlower(tile) && rank(tile) in 2..8

    /** 数牌序号，用于判断同一花色下的连续性。 */
    fun tileOf(suit: Int, rank: Int): Int = when (suit) {
        0 -> rank - 1
        1 -> 9 + rank - 1
        2 -> 18 + rank - 1
        else -> 26 + rank
    }

    fun name(tile: Int): String = when (tile) {
        in 0..8 -> "${CN_NUM[rank(tile)]}万"
        in 9..17 -> "${CN_NUM[rank(tile)]}筒"
        in 18..26 -> "${CN_NUM[rank(tile)]}条"
        27 -> "东"
        28 -> "南"
        29 -> "西"
        30 -> "北"
        31 -> "中"
        32 -> "发"
        33 -> "白"
        34 -> "春"
        35 -> "夏"
        36 -> "秋"
        37 -> "冬"
        38 -> "梅"
        39 -> "兰"
        40 -> "竹"
        41 -> "菊"
        else -> "?"
    }

    fun names(tiles: Iterable<Int>): String = tiles.joinToString(" ") { name(it) }

    /** 解析单个牌名，支持 "一万"/"1万"/"东"/"中" 等写法。 */
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        when (t) {
            "东" -> return EAST
            "南" -> return SOUTH
            "西" -> return WEST
            "北" -> return NORTH
            "中" -> return RED
            "发" -> return GREEN
            "白" -> return WHITE
            "春" -> return 34
            "夏" -> return 35
            "秋" -> return 36
            "冬" -> return 37
            "梅" -> return 38
            "兰" -> return 39
            "竹" -> return 40
            "菊" -> return 41
        }
        if (t.length < 2) return null
        val numText = t.dropLast(1)
        val suit = when (t.last()) {
            '万', '萬' -> 0
            '筒' -> 1
            '条', '條' -> 2
            else -> return null
        }
        val rank = when (numText) {
            "1", "一" -> 1
            "2", "二", "两", "兩" -> 2
            "3", "三" -> 3
            "4", "四" -> 4
            "5", "五" -> 5
            "6", "六" -> 6
            "7", "七" -> 7
            "8", "八" -> 8
            "9", "九" -> 9
            else -> return null
        }
        return tileOf(suit, rank)
    }

    fun parseList(text: String): List<Int> {
        val cleaned = text.replace(",", " ").replace("，", " ").replace("/", " ")
        return cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { parse(it) }
    }

    private val CN_NUM = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
}
