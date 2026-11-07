package mahjong.core.score

import mahjong.core.analysis.HandAnalyzer
import mahjong.core.analysis.SpecialHand
import mahjong.core.model.HandShape
import mahjong.core.model.Meld
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig
import mahjong.core.rules.RulePreset
import mahjong.core.tile.Tiles

/**
 * 广东麻将（推倒胡 / 鸡平胡）计分。
 *
 * 采用“牌型不累积、取最大牌型 + 附加番”的结构：
 * - 推倒胡：只允许自摸，牌型取最大，封顶 3 番；
 * - 鸡平胡：允许点炮/一炮多响，大牌型可突破封顶，附加番只在不突破封顶时累加。
 */
object GuangdongScorer {

    private data class Pattern(val id: String, val name: String, val fan: Int)

    fun score(
        tiles: List<Int>,
        melds: List<Meld>,
        ctx: WinContext,
        config: RuleConfig,
    ): WinResult? {
        if (!HandAnalyzer.isWinningHand(tiles, melds)) return null
        if (ctx.selfDraw && !config.allowSelfDrawWin) return null
        if (!ctx.selfDraw && !config.allowDiscardWin && !ctx.robKong) return null

        val patterns = ArrayList<Pattern>()
        val special = HandAnalyzer.specialHand(tiles, melds.size)
        if (special != null) {
            patterns.add(specialPattern(special, tiles))
        } else {
            val shapePatterns = HandAnalyzer.winShapes(tiles, melds).mapNotNull { primaryPattern(it, ctx) }
            patterns.addAll(shapePatterns)
        }
        val primary = patterns.maxByOrNull { it.fan }
            ?: Pattern("gd_chicken", "鸡胡", 0).takeIf { config.allowChickenHand }
            ?: return null

        val addOns = ArrayList<FanItem>()
        if (ctx.selfDraw) addOns.add(FanItem("gd_self_draw", "自摸", 1))
        if (ctx.selfDraw && ctx.kongReplacement) addOns.add(FanItem("gd_kong_bloom", "杠上开花", 1))
        if (ctx.robKong) addOns.add(FanItem("gd_rob_kong", "抢杠", 1))
        if (ctx.lastTile) addOns.add(FanItem("gd_last_tile", "海底", 1))
        if (config.flowerFan > 0) {
            ctx.flowers.forEach {
                addOns.add(FanItem("gd_flower_$it", Tiles.name(it), config.flowerFan))
            }
        }
        addOns.addAll(valueHonorAddOns(tiles, melds, ctx))

        val cap = config.maxFanCap
        val addOnTotal = addOns.sumOf { it.totalFan }
        val effective = if (primary.fan > cap) primary.fan else minOf(cap, primary.fan + addOnTotal)

        val fanItems = ArrayList<FanItem>()
        fanItems.add(FanItem("gd_${primary.id}", primary.name, primary.fan))
        fanItems.addAll(addOns)

        return WinResult(
            winner = ctx.seat,
            fromPlayer = ctx.fromPlayer,
            winningTile = ctx.winningTile,
            selfDraw = ctx.selfDraw,
            robKong = ctx.robKong,
            fanItems = fanItems,
            totalFan = effective,
            thresholdFan = effective,
            points = ScoreEngine.pointsFor(config, effective),
            handTiles = tiles.sorted(),
            melds = melds,
        )
    }

    private fun specialPattern(special: SpecialHand, tiles: List<Int>): Pattern = when (special) {
        SpecialHand.THIRTEEN_ORPHANS -> Pattern("thirteen_orphans", "十三幺", 6)
        SpecialHand.NINE_GATES -> Pattern("nine_gates", "九莲宝灯", 6)
        SpecialHand.SEVEN_SHIFTED_PAIRS -> Pattern("seven_shifted", "连七对", 6)
        SpecialHand.SEVEN_PAIRS -> {
            val suits = tiles.filter { !Tiles.isHonor(it) }.map { Tiles.suit(it) }.distinct()
            val honors = tiles.count { Tiles.isHonor(it) }
            when {
                honors == tiles.size -> Pattern("all_honors", "字一色", 6)
                suits.size == 1 && honors == 0 -> Pattern("full_flush_pairs", "清一色七对", 4)
                suits.size == 1 -> Pattern("half_flush_pairs", "混一色七对", 2)
                else -> Pattern("seven_pairs", "七对", 2)
            }
        }

        SpecialHand.GREATER_HONORS_AND_KNITTED,
        SpecialHand.LESSER_HONORS_AND_KNITTED,
        -> Pattern("knitted", "全不靠", 4)
    }

    private fun primaryPattern(shape: HandShape, ctx: WinContext): Pattern? {
        val groups = shape.groups
        val chows = groups.count { it.isChow }
        val pungs = groups.count { !it.isChow }
        val all = shape.allTiles
        val honors = all.count { Tiles.isHonor(it) }
        val suits = all.filter { !Tiles.isHonor(it) }.map { Tiles.suit(it) }.distinct()
        val pair = shape.pair

        val allPungs = pungs == 4
        val pure = honors == 0 && suits.size == 1
        val half = honors > 0 && suits.size == 1
        val allHonors = honors == all.size
        val allTerminals = honors == 0 && all.all { Tiles.isTerminal(it) }
        val allTerminalsHonors = all.all { Tiles.isTerminalOrHonor(it) }
        val allGreen = all.all { isGreen(it) }

        val candidates = ArrayList<Pattern>(8)
        if (shape.groups.count { Tiles.isWind(it.tile) } == 4) candidates.add(Pattern("big_four_winds", "大四喜", 6))
        if (shape.groups.count { Tiles.isDragon(it.tile) } == 3) candidates.add(Pattern("big_three_dragons", "大三元", 6))
        if (allHonors) candidates.add(Pattern("all_honors", "字一色", 6))
        if (allTerminals) candidates.add(Pattern("all_terminals", "清幺九", 6))
        if (allGreen) candidates.add(Pattern("all_green", "绿一色", 6))
        if (allTerminalsHonors) candidates.add(Pattern("all_term_honor", "混幺九", 5))

        val windPungs = shape.groups.count { Tiles.isWind(it.tile) }
        if (windPungs == 3 && Tiles.isWind(pair)) candidates.add(Pattern("little_four_winds", "小四喜", 5))
        val dragonPungs = shape.groups.count { Tiles.isDragon(it.tile) }
        if (dragonPungs == 2 && Tiles.isDragon(pair)) candidates.add(Pattern("little_three_dragons", "小三元", 5))

        if (pure && allPungs) candidates.add(Pattern("pure_pung", "清碰", 5))
        else if (pure) candidates.add(Pattern("full_flush", "清一色", 4))
        else if (half && allPungs) candidates.add(Pattern("half_pung", "混碰", 4))
        else if (half) candidates.add(Pattern("half_flush", "混一色", 2))

        if (allPungs && !pure && !half) candidates.add(Pattern("all_pungs", "碰碰胡", 2))
        if (chows == 4 && !Tiles.isHonor(pair)) candidates.add(Pattern("ping_hu", "平胡", 1))

        return candidates.maxByOrNull { it.fan }
    }

    private fun valueHonorAddOns(
        tiles: List<Int>,
        melds: List<Meld>,
        ctx: WinContext,
    ): List<FanItem> {
        val pungs = melds.filter { it.type == mahjong.core.model.MeldType.PUNG ||
            it.type == mahjong.core.model.MeldType.KONG_EXPOSED ||
            it.type == mahjong.core.model.MeldType.KONG_ADDED ||
            it.type == mahjong.core.model.MeldType.KONG_CONCEALED }
        // 手里未副露的刻子也计入（暗刻）
        val tilesCount = HandAnalyzer.counts(tiles)
        val concealedPungs = (0..33).filter { tilesCount[it] >= 3 }.map { it to tilesCount[it] }
        val pungTiles = pungs.map { it.baseTile } + concealedPungs.filter { it.second >= 3 }.map { it.first }

        val result = ArrayList<FanItem>()
        for (t in pungTiles) {
            if (Tiles.isDragon(t)) result.add(FanItem("gd_dragon_pung", "箭刻", 1))
            else if (t == 27 + ctx.roundWind) result.add(FanItem("gd_round_wind", "圈风刻", 1))
            else if (t == 27 + ctx.seatWind) result.add(FanItem("gd_seat_wind", "门风刻", 1))
        }
        return result
    }

    private fun isGreen(tile: Int): Boolean = tile in setOf(19, 20, 21, 23, 25, 32)
}
