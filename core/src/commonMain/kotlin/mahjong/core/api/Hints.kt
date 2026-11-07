package mahjong.core.api

import mahjong.core.analysis.HandAnalyzer
import mahjong.core.model.Phase
import mahjong.core.model.PlayerView
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig
import mahjong.core.score.FanItem
import mahjong.core.score.ScoreEngine
import mahjong.core.score.WinContext
import mahjong.core.score.WinResult

/** 和牌方式。 */
enum class WinType {
    SELF_DRAW,
    DISCARD,
    ROB_KONG,
}

/**
 * 番数与得分预览。
 *
 * - [fan]：广东为封顶后的有效番；国标为实际总番。
 * - [thresholdFan]：国标为不计花牌的起和番，广东与 [fan] 相同。
 * - [points]：引擎基本分（国标=番，广东=2^番），未乘底分、未计包牌/买马。
 * - [gain]：预计一局收取的总分（国标含 8 分底分；广东按人数），未计包牌/买马。
 * - [meetsMinimum]：是否达到起和门槛。国标「听牌但不足 8 分」时为 false。
 * - [fanCap]：本场封顶番数；null 表示不封顶（国标）。界面显示封顶线应读这个字段，不要写死。
 */
data class FanPreview(
    val fan: Int,
    val thresholdFan: Int,
    val points: Int,
    val gain: Int,
    val meetsMinimum: Boolean,
    val fanItems: List<FanItem>,
    /** 本场封顶番数；null 表示不封顶。广东大牌型可突破该值，以 [fan] 为准。 */
    val fanCap: Int? = null,
) {
    val fanSummary: String get() = fanItems.joinToString(" + ") { it.toString() }
}

/** 一个听口：牌种、剩余张数，以及自摸/点炮两种和法的番数预览。 */
data class WaitHint(
    val tile: Int,
    val remaining: Int,
    val selfDraw: FanPreview?,
    val discard: FanPreview?,
) {
    val meetsMinimum: Boolean
        get() = selfDraw?.meetsMinimum == true || discard?.meetsMinimum == true

    /** 该听口下番数最高的和法（不足以起和时仍返回，便于界面显示“差多少番”）。 */
    val bestPreview: FanPreview?
        get() = listOfNotNull(selfDraw, discard).maxByOrNull { it.fan }
}

/** 打掉某张手牌后的听牌情况。 */
data class DiscardHint(
    val tile: Int,
    /** 打掉后的向听数；0 即听牌。 */
    val shanten: Int,
    val tenpai: Boolean,
    val waits: List<WaitHint>,
    val totalRemaining: Int,
) {
    val meetsMinimum: Boolean get() = waits.any { it.meetsMinimum }
    val bestPreview: FanPreview? get() = waits.mapNotNull { it.bestPreview }.maxByOrNull { it.fan }
}

/** 当前手牌（13 张形态）的听口。14 张时请使用 [Hints.discardHints]。 */
data class TenpaiHint(
    val seat: Int,
    val tenpai: Boolean,
    val shanten: Int,
    val waits: List<WaitHint>,
    val totalRemaining: Int,
) {
    val meetsMinimum: Boolean get() = waits.any { it.meetsMinimum }
    val bestPreview: FanPreview? get() = waits.mapNotNull { it.bestPreview }.maxByOrNull { it.fan }
}

/** 当前可选的胡牌方式，供胡按钮显示番型预览。 */
data class WinHint(
    val type: WinType,
    val winningTile: Int,
    val fromPlayer: Int?,
    val preview: FanPreview,
)

/**
 * 界面可直接传入的额外胡牌机会（如抢杠）。
 * 自摸/点炮会从 [PlayerView] 自动推导，无需重复传入。
 */
data class WinOpportunity(
    val type: WinType,
    val tile: Int,
    val fromPlayer: Int? = null,
    val kongReplacement: Boolean = false,
    val lastTile: Boolean = false,
)

/** 一次查询返回该座位的全部提示。 */
data class SeatHints(
    val seat: Int,
    val discards: List<DiscardHint>,
    val tenpai: TenpaiHint,
    val wins: List<WinHint>,
)

/**
 * 听牌/胡牌提示：纯查询，不修改任何牌局状态。
 *
 * 只依赖 [PlayerView]（本座位手牌 + 公开信息），不含任何别家手牌；
 * 因此单机 Compose 与联机客户端都可以本地计算。胡牌合法性最终仍以服务端为准
 * （例如漏胡、服务端额外校验等本接口无法感知的状态）。
 *
 * 内部复用 AI 同款 [HandAnalyzer] 与 [ScoreEngine]，不另写一套判定。
 */
object Hints {

    /** 一次调用拿齐三类提示。 */
    fun analyze(view: PlayerView): SeatHints = SeatHints(
        seat = view.seat,
        discards = discardHints(view),
        tenpai = tenpaiHint(view),
        wins = winHints(view),
    )

    /**
     * 出牌建议：手牌为 14-3×副露 张（摸牌后）时，对每张可打出的牌给出结果。
     * 非该形态返回空列表。
     */
    fun discardHints(view: PlayerView): List<DiscardHint> {
        val meldCount = view.melds.size
        if (view.hand.size != 14 - meldCount * 3) return emptyList()

        val config = view.config
        val scoringConfig = relaxedForDisplay(config)
        val publicTiles = publicTiles(view)
        val seen = seenCounts(view)

        return view.hand.distinct().sorted().map { tile ->
            val rest = view.hand.toMutableList().also { it.remove(tile) }
            val shanten = HandAnalyzer.shanten(rest, meldCount)
            if (shanten != 0) {
                DiscardHint(tile, shanten, tenpai = false, waits = emptyList(), totalRemaining = 0)
            } else {
                val waits = HandAnalyzer.waits(rest, meldCount).map { wait ->
                    waitHint(view, rest, wait, seen, config, scoringConfig, publicTiles)
                }
                DiscardHint(
                    tile = tile,
                    shanten = 0,
                    tenpai = true,
                    waits = waits,
                    totalRemaining = waits.sumOf { it.remaining },
                )
            }
        }
    }

    /** 当前听口：仅在 13-3×副露 张（等待和牌）时给出听牌信息。 */
    fun tenpaiHint(view: PlayerView): TenpaiHint {
        val meldCount = view.melds.size
        val hand = view.hand
        if (hand.size != 13 - meldCount * 3) {
            return TenpaiHint(
                seat = view.seat,
                tenpai = false,
                shanten = HandAnalyzer.shanten(hand, meldCount),
                waits = emptyList(),
                totalRemaining = 0,
            )
        }
        val shanten = HandAnalyzer.shanten(hand, meldCount)
        if (shanten != 0) {
            return TenpaiHint(view.seat, tenpai = false, shanten = shanten, waits = emptyList(), totalRemaining = 0)
        }

        val config = view.config
        val scoringConfig = relaxedForDisplay(config)
        val publicTiles = publicTiles(view)
        val seen = seenCounts(view)
        val waits = HandAnalyzer.waits(hand, meldCount).map { wait ->
            waitHint(view, hand, wait, seen, config, scoringConfig, publicTiles)
        }
        return TenpaiHint(
            seat = view.seat,
            tenpai = true,
            shanten = 0,
            waits = waits,
            totalRemaining = waits.sumOf { it.remaining },
        )
    }

    /**
     * 当前可胡提示：自摸/点炮从 [PlayerView] 自动推导，
     * 抢杠等额外机会由 [opportunities] 传入。只返回引擎判定成立且达到起和门槛的选项。
     */
    fun winHints(view: PlayerView, opportunities: List<WinOpportunity> = emptyList()): List<WinHint> {
        val config = view.config
        val publicTiles = publicTiles(view)
        val result = LinkedHashMap<String, WinHint>()

        fun add(
            type: WinType,
            tile: Int,
            fromPlayer: Int?,
            kongReplacement: Boolean,
            lastTile: Boolean,
        ) {
            when (type) {
                WinType.SELF_DRAW -> if (!config.allowSelfDrawWin) return
                WinType.DISCARD -> if (!config.allowDiscardWin) return
                WinType.ROB_KONG -> if (!config.allowRobKong) return
            }
            // 自摸时手牌已包含和牌张；点炮/抢杠时才把牌加进来。
            val hand = if (type == WinType.SELF_DRAW) view.hand else view.hand + tile
            if (!HandAnalyzer.isWinningHand(hand, view.melds)) return
            val ctx = WinContext(
                seat = view.seat,
                selfDraw = type == WinType.SELF_DRAW,
                winningTile = tile,
                fromPlayer = fromPlayer,
                robKong = type == WinType.ROB_KONG,
                kongReplacement = kongReplacement,
                lastTile = lastTile,
                seatWind = view.seatWind,
                roundWind = view.roundWind,
                flowers = view.flowers,
                dealer = view.dealer,
                visibleTiles = publicTiles,
            )
            val win = ScoreEngine.score(config, hand, view.melds, ctx) ?: return
            val preview = preview(win, config, type == WinType.SELF_DRAW)
            val key = "$type-$tile-$fromPlayer"
            result.putIfAbsent(key, WinHint(type, tile, fromPlayer, preview))
        }

        if (view.canSelfDrawWin) {
            view.lastDrawnTile?.let { tile ->
                add(
                    WinType.SELF_DRAW,
                    tile,
                    fromPlayer = null,
                    kongReplacement = view.lastDrawWasKongReplacement,
                    lastTile = view.wallRemaining == 0,
                )
            }
        }

        val discard = view.lastDiscard
        if (view.phase == Phase.AWAIT_CLAIM && discard != null && discard.player != view.seat) {
            add(
                WinType.DISCARD,
                discard.tile,
                fromPlayer = discard.player,
                kongReplacement = false,
                lastTile = discard.lastTileOfWall,
            )
        }

        for (op in opportunities) {
            add(op.type, op.tile, op.fromPlayer, op.kongReplacement, op.lastTile)
        }
        return result.values.toList()
    }

    // ------------------------------------------------------------ 内部实现

    /**
     * 国标计分器在不足起和门槛时返回 null，这里临时把门槛降到 0 以便仍然给出真实番数；
     * 是否达标由 [preview] 用原始配置单独标注。广东无门槛，原样使用。
     */
    private fun relaxedForDisplay(config: RuleConfig): RuleConfig =
        if (config.mode == GameMode.MCR && config.minFan > 0) config.copy(minFan = 0) else config

    private fun waitHint(
        view: PlayerView,
        rest: List<Int>,
        tile: Int,
        seen: IntArray,
        config: RuleConfig,
        scoringConfig: RuleConfig,
        publicTiles: List<Int>,
    ): WaitHint {
        val hand = rest + tile
        val selfDraw = ScoreEngine.score(
            scoringConfig,
            hand,
            view.melds,
            WinContext(
                seat = view.seat,
                selfDraw = true,
                winningTile = tile,
                fromPlayer = null,
                seatWind = view.seatWind,
                roundWind = view.roundWind,
                flowers = view.flowers,
                dealer = view.dealer,
                visibleTiles = publicTiles,
            ),
        )?.let { preview(it, config, selfDraw = true) }

        val discard = if (config.allowDiscardWin) {
            ScoreEngine.score(
                scoringConfig,
                hand,
                view.melds,
                WinContext(
                    seat = view.seat,
                    selfDraw = false,
                    winningTile = tile,
                    fromPlayer = null,
                    seatWind = view.seatWind,
                    roundWind = view.roundWind,
                    flowers = view.flowers,
                    dealer = view.dealer,
                    // 点炮时和牌张会进入牌池，和绝张判定要把它算作已见。
                    visibleTiles = publicTiles + tile,
                ),
            )?.let { preview(it, config, selfDraw = false) }
        } else {
            null
        }

        return WaitHint(tile, (4 - seen[tile]).coerceAtLeast(0), selfDraw, discard)
    }

    private fun preview(win: WinResult, config: RuleConfig, selfDraw: Boolean): FanPreview {
        val meetsMinimum = if (config.mode == GameMode.MCR) {
            win.thresholdFan >= config.minFan
        } else {
            true
        }
        return FanPreview(
            fan = win.totalFan,
            thresholdFan = win.thresholdFan,
            points = win.points,
            gain = estimatedGain(config, win.points, selfDraw),
            meetsMinimum = meetsMinimum,
            fanItems = win.fanItems,
            fanCap = config.effectiveFanCap,
        )
    }

    /** 与 Settlement 口径保持一致，未计包牌/买马。 */
    private fun estimatedGain(config: RuleConfig, points: Int, selfDraw: Boolean): Int =
        if (config.mode == GameMode.MCR) {
            val base = config.mcrBaseScore
            if (selfDraw) 3 * (base + points) else (base + points) + 2 * base
        } else {
            if (selfDraw) 3 * points else points
        }

    /** 场上公开牌：各家副露、弃牌、花牌；不含任何手牌。 */
    private fun publicTiles(view: PlayerView): List<Int> {
        val result = ArrayList<Int>(96)
        result.addAll(view.melds.flatMap { it.tiles })
        result.addAll(view.ownDiscards)
        result.addAll(view.flowers)
        for (o in view.opponents) {
            result.addAll(o.melds.flatMap { it.tiles })
            result.addAll(o.discards)
        }
        return result
    }

    /** 已见牌计数（0..33），含自己手牌与全部公开牌。 */
    private fun seenCounts(view: PlayerView): IntArray {
        val counts = IntArray(34)
        fun add(tile: Int) {
            if (tile in 0..33) counts[tile]++
        }
        view.hand.forEach(::add)
        view.melds.forEach { meld -> meld.tiles.forEach(::add) }
        view.flowers.forEach(::add)
        view.ownDiscards.forEach(::add)
        for (o in view.opponents) {
            o.melds.forEach { meld -> meld.tiles.forEach(::add) }
            o.discards.forEach(::add)
        }
        return counts
    }
}
