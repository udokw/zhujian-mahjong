package mahjong.cli

import mahjong.core.ai.AiBot
import mahjong.core.ai.AiLevel
import mahjong.core.ai.MahjongBot
import mahjong.core.ai.Simulator
import mahjong.core.engine.MahjongGame
import mahjong.core.model.GameEvent
import mahjong.core.model.GameResult
import mahjong.core.rules.RuleConfig
import mahjong.core.rules.RulePreset
import mahjong.core.tile.Tiles
import kotlin.system.measureTimeMillis

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
        printHelp()
        return
    }
    val command = args[0]
    val opts = parseOptions(args.drop(1))
    val preset = RulePreset.fromId(opts["preset"] ?: "mcr_1998")
    if (preset == null) {
        System.err.println("未知规则集: ${opts["preset"]}")
        printHelp()
        return
    }
    val config = RuleConfig.of(preset)
    val seed = opts["seed"]?.toLongOrNull() ?: 42L

    when (command) {
        "play" -> play(config, seed, opts)
        "simulate", "sim" -> simulate(config, seed, opts)
        else -> {
            System.err.println("未知命令: $command")
            printHelp()
        }
    }
}

private fun play(config: RuleConfig, seed: Long, opts: Map<String, String>) {
    val levels = parseLevels(opts["levels"])
    val bots: List<MahjongBot> = (0..3).map { seat ->
        AiBot(levels[seat % levels.size], seed + seat * 97L)
    }
    println("=== 单局模拟 ===")
    println("规则: ${config.preset.displayName} (${config.preset.id})  用牌: ${config.buildTileSet().size} 张")
    println("种子: $seed  起和: ${config.minFan} 番  封顶: ${config.maxFanCap} 番")
    println("座位 AI: " + bots.joinToString(", ") { "P${bots.indexOf(it)}=" + it.level.displayName })

    val game = MahjongGame(config = config, seed = seed, dealer = 0, roundWind = 0)
    game.drainEvents().forEach { println(formatEvent(it, game.dealer)) }

    var guard = 0
    while (!game.isOver && guard++ < 20000) {
        val seat = game.actor
        if (seat == null) {
            println("!! 无待行动座位，phase=${game.state.phase} 牌墙=${game.state.wallRemaining}")
            break
        }
        val legal = game.legalActions(seat)
        if (legal.isEmpty()) {
            println("!! 座位 $seat 无合法动作，phase=${game.state.phase} 牌墙=${game.state.wallRemaining}")
            break
        }
        val action = bots[seat].choose(game.viewFor(seat), legal)
        println("  -> ${seatName(seat, game.dealer)}: ${formatAction(action)}")
        game.apply(action).forEach { println(formatEvent(it, game.dealer)) }
    }

    val result = game.gameResult ?: run {
        println("!! 对局未正常结束")
        return
    }
    printResult(result, game)
}

private fun simulate(config: RuleConfig, seed: Long, opts: Map<String, String>) {
    val games = opts["games"]?.toIntOrNull() ?: 40
    val levels = parseLevels(opts["levels"])
    println("=== 批量自动对局 ===")
    println("规则: ${config.preset.displayName} (${config.preset.id})  种子: $seed  每组对局: $games")
    println("对手: 其余三家固定为「普通」AI，待测难度轮换座位与庄家。")
    println()

    val elapsed = measureTimeMillis {
        val stats = Simulator.runBatch(config, games, seed, levels)
        println(
            pad("难度", 8) + pad("对局", 8) + pad("胜率", 9) + pad("自摸率", 10) +
                pad("放铳率", 9) + pad("荒庄率", 9) + pad("平均番", 10) + pad("平均分", 10)
        )
        println("-".repeat(73))
        for (s in stats) {
            println(
                pad(s.level.displayName, 8) +
                    pad(s.games.toString(), 8) +
                    pad(pct(s.winRate), 9) +
                    pad(pct(if (s.games == 0) 0.0 else s.selfDraws.toDouble() / s.games), 10) +
                    pad(pct(s.dealInRate), 9) +
                    pad(pct(s.drawRate), 9) +
                    pad(fmt(s.avgFan), 10) +
                    pad(fmt(s.avgScoreDelta), 10)
            )
        }
    }
    println()
    println("耗时: ${elapsed} ms")
}

private fun printResult(result: GameResult, game: MahjongGame) {
    println()
    println("--- 结算 ---")
    if (result.draw) {
        println("荒庄: ${result.reason}")
    } else {
        for (w in result.winners) {
            println(
                "${seatName(w.winner, game.dealer)} 和牌 " +
                    "(${Tiles.name(w.winningTile)}) 方式=${if (w.selfDraw) "自摸" else "点炮"}" +
                    (if (w.robKong) "·抢杠" else "")
            )
            println("  手牌: ${Tiles.names(w.handTiles)}")
            println("  番种: ${w.fanSummary.ifEmpty { "无" }}")
            println("  总番: ${w.totalFan}  基本分: ${w.points}")
        }
    }
    println("分数变化: " + (0..3).joinToString("  ") { "${seatName(it, game.dealer)}=${signed(result.deltaScores[it])}" })
    println("庄家${if (result.dealerContinues) "连庄" else "轮转"}  原因: ${result.reason}")
    if (result.horses.isNotEmpty()) {
        println("买马: " + result.horses.joinToString(", ") { it.description })
    }
}

private fun formatEvent(e: GameEvent, dealer: Int): String = when (e) {
    is GameEvent.HandStarted ->
        "[T${e.turn}] 开局 庄家=${seatName(e.dealer, dealer)} 圈风=${windName(e.roundWind)} 牌墙=${e.wallCount}"
    is GameEvent.Dealt -> "[T${e.turn}] 发牌 ${seatName(e.seat, dealer)} ${e.tileCount} 张"
    is GameEvent.TileDrawn -> {
        val tags = buildList {
            if (e.fromTail) add("牌尾")
            if (e.kongReplacement) add("杠后补牌")
            if (e.flowerReplacement) add("补花")
        }.joinToString("·")
        "[T${e.turn}] 摸牌 ${seatName(e.seat, dealer)} ${Tiles.name(e.tile)}" +
            if (tags.isNotEmpty()) " [$tags]" else ""
    }
    is GameEvent.FlowerRevealed ->
        "[T${e.turn}] 补花 ${seatName(e.seat, dealer)} ${Tiles.name(e.tile)}"
    is GameEvent.TileDiscarded ->
        "[T${e.turn}] 打牌 ${seatName(e.seat, dealer)} ${Tiles.name(e.tile)}" +
            if (e.lastTileOfWall) " [海底]" else ""
    is GameEvent.MeldFormed ->
        "[T${e.turn}] 副露 ${seatName(e.seat, dealer)} ${e.meld.type} ${Tiles.names(e.meld.tiles)}"
    is GameEvent.KongDeclared ->
        "[T${e.turn}] 杠 ${seatName(e.seat, dealer)} ${Tiles.name(e.tile)} ${e.meldType}"
    is GameEvent.ClaimWindowOpened ->
        "[T${e.turn}] 响应窗口 ${seatName(e.discarder, dealer)} 打出 ${Tiles.name(e.tile)}，等待 " +
            e.claimants.joinToString(",") { seatName(it, dealer) }
    is GameEvent.RobKongWindowOpened ->
        "[T${e.turn}] 抢杠窗口 ${seatName(e.kongPlayer, dealer)} 加杠 ${Tiles.name(e.tile)}"
    is GameEvent.GangScored ->
        "[T${e.turn}] 杠分 ${seatName(e.seat, dealer)} +${e.points} (${e.description})"
    is GameEvent.WinDeclared ->
        "[T${e.turn}] 和牌 ${seatName(e.result.winner, dealer)} ${e.result.totalFan} 番: ${e.result.fanSummary}"
    is GameEvent.DrawGame -> "[T${e.turn}] 荒庄: ${e.reason}"
    is GameEvent.GameOver -> "[T${e.turn}] 对局结束"
}

private fun formatAction(action: mahjong.core.model.Action): String = when (action) {
    is mahjong.core.model.Action.Discard -> "打 ${Tiles.name(action.tile)}"
    is mahjong.core.model.Action.Chi -> "吃 ${Tiles.name(action.claimed)} (${Tiles.names(action.tiles)})"
    is mahjong.core.model.Action.Peng -> "碰 ${Tiles.name(action.tile)}"
    is mahjong.core.model.Action.ExposedKong -> "明杠 ${Tiles.name(action.tile)}"
    is mahjong.core.model.Action.ConcealedKong -> "暗杠 ${Tiles.name(action.tile)}"
    is mahjong.core.model.Action.AddedKong -> "加杠 ${Tiles.name(action.tile)}"
    is mahjong.core.model.Action.Hu -> "胡"
    is mahjong.core.model.Action.Pass -> "过"
}

private fun parseLevels(raw: String?): List<AiLevel> {
    if (raw == null) return AiLevel.entries.toList()
    val levels = raw.split(',').mapNotNull { AiLevel.fromId(it.trim()) }
    return if (levels.isEmpty()) AiLevel.entries.toList() else levels
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val map = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val body = a.removePrefix("--")
            val eq = body.indexOf('=')
            if (eq >= 0) {
                map[body.substring(0, eq)] = body.substring(eq + 1)
            } else {
                val value = if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    i++
                    args[i]
                } else {
                    "true"
                }
                map[body] = value
            }
        }
        i++
    }
    return map
}

private fun seatName(seat: Int, dealer: Int): String {
    val wind = (seat - dealer + 4) % 4
    return "P$seat(${windName(wind)})"
}

private fun windName(wind: Int): String = when (wind) {
    0 -> "东"
    1 -> "南"
    2 -> "西"
    else -> "北"
}

private fun pct(v: Double): String = "${(v * 1000).toInt() / 10.0}%"

private fun fmt(v: Double): String = "${(v * 100).toInt() / 100.0}"

private fun signed(v: Int): String = if (v > 0) "+$v" else v.toString()

private fun pad(s: String, width: Int): String {
    val cells = s.fold(0) { acc, ch -> acc + if (ch.code > 127) 2 else 1 }
    return if (cells < width) s + " ".repeat(width - cells) else s
}

private fun printHelp() {
    println(
        """
        麻将规则引擎 CLI

        用法:
          play     --preset mcr_1998 --seed 42 [--levels novice,normal,expert,master]
          simulate --preset gd_tuidaohu --seed 42 --games 40 [--levels ...]

        规则预设:
          mcr_1998      国标 144 张，8 分起和，不连庄，一炮单响
          gd_tuidaohu   广东推倒胡 136 张，不可吃，仅自摸，3 番封顶，连庄，买马
          gd_jipinghu   广东鸡平胡 144 张，可吃可点炮，一炮多响，包牌

        选项:
          --seed <long>       随机种子（默认 42）
          --games <int>       每组批量对局数（默认 40）
          --levels <list>     AI 难度，逗号分隔：novice,normal,expert,master
        """.trimIndent()
    )
}
