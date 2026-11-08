package mahjong.app.lan

import mahjong.core.analysis.HandAnalyzer
import mahjong.core.engine.MahjongGame
import mahjong.core.model.Action
import mahjong.core.model.Phase
import mahjong.core.model.PlayerView
import mahjong.core.tile.Tiles
import kotlin.random.Random

/**
 * 服务端 AI，四档难度：
 * 1. 新手：合法动作 + 随机倾向
 * 2. 普通（**联机托管固定使用**）：向听/牌效 + 基础防守
 * 3. 高手：牌效权重更高 + 留对子 + 更积极副露
 * 4. 大师：在 3 的基础上加入危险牌推断
 *
 * AI 只消费 core 的 [PlayerView]，与真人客户端拿到的裁剪信息完全一致。
 */
class AiBrain(val level: Int, private val random: Random = Random.Default) {

    fun decide(game: MahjongGame, seat: Int): Action? {
        val legal = game.legalActions(seat)
        if (legal.isEmpty()) return null
        val view = game.viewFor(seat)

        if (legal.any { it is Action.Hu }) return Action.Hu(seat)

        if (view.phase == Phase.AWAIT_DISCARD && view.currentPlayer == seat) {
            if (level >= 2) {
                legal.filterIsInstance<Action.ConcealedKong>().firstOrNull()?.let { return it }
                legal.filterIsInstance<Action.AddedKong>().firstOrNull()?.let { return it }
            }
            val discards = legal.filterIsInstance<Action.Discard>()
            if (discards.isEmpty()) return legal.firstOrNull()
            return Action.Discard(seat, chooseDiscard(view))
        }

        val kongs = legal.filterIsInstance<Action.ExposedKong>()
        val pengs = legal.filterIsInstance<Action.Peng>()
        val chis = legal.filterIsInstance<Action.Chi>()

        if (level <= 1) {
            return if (pengs.isNotEmpty() && random.nextDouble() < 0.3) pengs.first() else Action.Pass(seat)
        }
        if (kongs.isNotEmpty()) return kongs.first()

        val base = HandAnalyzer.shanten(view.hand, view.meldCount)
        if (pengs.isNotEmpty()) {
            val rest = view.hand.toMutableList().apply { repeat(2) { remove(pengs.first().tile) } }
            val after = HandAnalyzer.shanten(rest, view.meldCount + 1)
            if (after <= base) return pengs.first()
        }
        if (chis.isNotEmpty()) {
            val best = chis.minByOrNull { shantenAfter(view, it.tiles) }!!
            val after = shantenAfter(view, best.tiles)
            if (after < base || (level >= 3 && after == base)) return best
        }
        return Action.Pass(seat)
    }

    private fun shantenAfter(view: PlayerView, tiles: List<Int>): Int {
        val rest = view.hand.toMutableList()
        if (!tiles.all { rest.remove(it) }) return Int.MAX_VALUE
        return HandAnalyzer.shanten(rest, view.meldCount + 1)
    }

    private fun chooseDiscard(view: PlayerView): Int {
        val hand = view.hand
        if (level <= 1 || hand.isEmpty()) return hand.randomOrNull(random) ?: 0

        val visible = visibleCounts(view)
        val threat = view.opponents.count { it.melds.size >= 2 }
        var bestTile = hand.first()
        var bestScore = Double.NEGATIVE_INFINITY

        for (tile in hand.distinct()) {
            val rest = hand.toMutableList().also { it.remove(tile) }
            val shanten = HandAnalyzer.shanten(rest, view.meldCount)
            val ukeire = HandAnalyzer.ukeireCount(rest, view.meldCount, visible)
            val safe = view.opponents.all { tile in it.discards }
            var score = -shanten * 1000.0 + ukeire * (if (level >= 3) 14.0 else 9.0)
            if (safe) score += 60.0
            if (threat > 0) score += if (safe) 90.0 else -45.0 * threat
            if (level >= 3) {
                val copies = hand.count { it == tile }
                score += if (copies == 1) 18.0 else -12.0 * copies
                if (Tiles.isTerminalOrHonor(tile)) score -= 6.0
            }
            if (level >= 4 && !safe && Tiles.isSimple(tile)) score -= 30.0
            score += random.nextDouble() * 0.5
            if (score > bestScore) {
                bestScore = score
                bestTile = tile
            }
        }
        return bestTile
    }

    private fun visibleCounts(view: PlayerView): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        fun add(tiles: Iterable<Int>) {
            for (t in tiles) counts[t] = (counts[t] ?: 0) + 1
        }
        add(view.hand)
        add(view.flowers)
        add(view.melds.flatMap { it.tiles })
        for (o in view.opponents) {
            add(o.melds.flatMap { it.tiles })
            add(o.discards)
        }
        return counts
    }
}
