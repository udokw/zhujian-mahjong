package mahjong.core.rules

import mahjong.core.tile.Tiles

enum class GameMode {
    MCR,
    GUANGDONG,
}

/** 产品对齐的三套预设，不再堆叠万能配置。 */
enum class RulePreset(val id: String, val displayName: String) {
    MCR_1998("mcr_1998", "国标标准场"),
    GD_TUIDAOHU("gd_tuidaohu", "广东推倒胡"),
    GD_JIPINGHU("gd_jipinghu", "广东鸡平胡"),
    ;

    companion object {
        fun fromId(id: String): RulePreset? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 两种玩法共用状态机，差异全部收敛在这里。
 */
data class RuleConfig(
    val preset: RulePreset = RulePreset.MCR_1998,
    val mode: GameMode = if (preset == RulePreset.MCR_1998) GameMode.MCR else GameMode.GUANGDONG,
    val useFlowers: Boolean = preset != RulePreset.GD_TUIDAOHU,
    val includeHonors: Boolean = true,
    /** 起和番/分门槛。 */
    val minFan: Int = if (preset == RulePreset.MCR_1998) 8 else 0,
    /** 国标：花牌分不计入起和门槛。 */
    val flowerCountsTowardMin: Boolean = false,
    /** 每张花牌计番/分。 */
    val flowerFan: Int = if (preset == RulePreset.GD_TUIDAOHU) 0 else 1,
    /** 广东：允许鸡胡（0 番按 1 倍底分）。 */
    val allowChickenHand: Boolean = mode == GameMode.GUANGDONG,
    /** 点炮和牌。推倒胡只允许自摸。 */
    val allowDiscardWin: Boolean = preset != RulePreset.GD_TUIDAOHU,
    val allowSelfDrawWin: Boolean = true,
    /** 一炮多响。国标固定单响。 */
    val multipleWinnersOnDiscard: Boolean = preset == RulePreset.GD_JIPINGHU,
    val allowChi: Boolean = preset != RulePreset.GD_TUIDAOHU,
    val allowKong: Boolean = true,
    /** 只允许抢加杠（引擎本身即此实现）。 */
    val allowRobKong: Boolean = true,
    /** 漏胡（过水）：鸡平胡开启。 */
    val missedWinRule: Boolean = preset == RulePreset.GD_JIPINGHU,
    /** 包牌：鸡平胡开启。 */
    val baoPai: Boolean = preset == RulePreset.GD_JIPINGHU,
    val dealerContinuesOnWin: Boolean = preset != RulePreset.MCR_1998,
    val dealerContinuesOnDraw: Boolean = preset != RulePreset.MCR_1998,
    /** 买马（广东两套默认开，国标不买马）。 */
    val buyHorseEnabled: Boolean = preset != RulePreset.MCR_1998,
    val buyHorseBase: Int = 1,
    val buyHorseIncrement: Int = 1,
    val buyHorseMax: Int = 4,
    val baseScore: Int = 1,
    /** 国标底分。 */
    val mcrBaseScore: Int = 8,
    /** 杠分：暗杠每家付、明杠放杠者付、补杠每家付。 */
    val gangScore: Int = 1,
    /** 广东封顶番数。 */
    val maxFanCap: Int = 3,
    val startingScore: Int = 100,
) {
    init {
        require(minFan >= 0) { "minFan must be >= 0" }
        require(buyHorseMax in 0..4) { "buyHorseMax must be in 0..4" }
        if (mode == GameMode.MCR) {
            require(includeHonors) { "MCR requires honor tiles" }
            require(!multipleWinnersOnDiscard) { "MCR only allows the nearest winner" }
            require(!dealerContinuesOnWin && !dealerContinuesOnDraw) { "MCR dealer does not repeat" }
            require(!buyHorseEnabled) { "MCR does not use horse tiles" }
        }
    }

    /**
     * 本场实际生效的封顶番数；null 表示不封顶。
     *
     * 国标不封顶，[maxFanCap] 只是兼容默认值，不参与 MCR 计分；
     * 广东返回 [maxFanCap]（大牌型仍可按引擎口径突破，以实际 WinResult.totalFan 为准）。
     * 界面显示封顶线应读这个字段，不要写死。
     */
    val effectiveFanCap: Int? get() = if (mode == GameMode.GUANGDONG) maxFanCap else null

    fun tileTypes(): List<Int> = (0..(if (includeHonors) 33 else 26)).toList()

    fun buildTileSet(): List<Int> {
        val result = ArrayList<Int>(144)
        for (t in tileTypes()) repeat(4) { result.add(t) }
        if (useFlowers) for (t in Tiles.flowerTiles) result.add(t)
        return result
    }

    fun seatWindName(wind: Int): String = when (wind) {
        0 -> "东"
        1 -> "南"
        2 -> "西"
        else -> "北"
    }

    /** 连庄次数 -> 本局买马匹数。 */
    fun horseCountFor(dealerStreak: Int): Int {
        if (!buyHorseEnabled) return 0
        return (buyHorseBase + dealerStreak * buyHorseIncrement).coerceIn(0, buyHorseMax)
    }

    companion object {
        fun of(preset: RulePreset): RuleConfig = when (preset) {
            RulePreset.MCR_1998 -> RuleConfig(preset = preset)
            RulePreset.GD_TUIDAOHU -> RuleConfig(
                preset = preset,
                mode = GameMode.GUANGDONG,
                useFlowers = false,
                flowerFan = 0,
                allowDiscardWin = false,
                multipleWinnersOnDiscard = false,
                allowChi = false,
                missedWinRule = false,
                baoPai = false,
                dealerContinuesOnWin = true,
                dealerContinuesOnDraw = true,
                buyHorseEnabled = true,
                maxFanCap = 3,
            )

            RulePreset.GD_JIPINGHU -> RuleConfig(
                preset = preset,
                mode = GameMode.GUANGDONG,
                useFlowers = true,
                flowerFan = 1,
                allowDiscardWin = true,
                multipleWinnersOnDiscard = true,
                allowChi = true,
                missedWinRule = true,
                baoPai = true,
                dealerContinuesOnWin = true,
                dealerContinuesOnDraw = true,
                buyHorseEnabled = true,
                maxFanCap = 3,
            )
        }

        fun mcr(): RuleConfig = of(RulePreset.MCR_1998)

        fun guangdongTuidaohu(): RuleConfig = of(RulePreset.GD_TUIDAOHU)

        fun guangdongJipinghu(): RuleConfig = of(RulePreset.GD_JIPINGHU)
    }
}
