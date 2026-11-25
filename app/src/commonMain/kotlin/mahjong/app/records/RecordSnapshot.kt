package mahjong.app.records

import mahjong.app.game.SettlementUi
import mahjong.app.game.WinnerUi
import mahjong.core.score.FanItem

/**
 * 把结算界面数据压成记录详情快照。
 *
 * 只在结算那一刻调用一次：番数、门槛判定、买马明细都在这里定稿，
 * 之后规则改动不会影响已经写入的历史记录。
 */
fun SettlementUi.toRecordDetail(mode: String, rule: String, time: String): RecordDetail {
    val winner = winners.firstOrNull()
    val winnerSeatUi = winner?.let { w -> seats.firstOrNull { it.seat == w.seat } }
    return RecordDetail(
        draw = draw,
        winType = winTypeOf(winner, draw),
        winnerSeat = winner?.seat ?: -1,
        winnerName = winner?.name ?: "",
        fromSeat = winner?.fromSeat,
        handTiles = winner?.handTiles ?: emptyList(),
        winningTile = winner?.winningTile ?: -1,
        melds = winner?.melds.orEmpty().map { RecordMeld(it.type.name, it.tiles) },
        flowers = winnerSeatUi?.flowers ?: emptyList(),
        fans = winner?.fans.orEmpty().map { it.toRecordFan(minFan, flowerCountsTowardMin) },
        totalFan = winner?.totalFan ?: 0,
        thresholdFan = winner?.thresholdFan ?: 0,
        points = winner?.points ?: 0,
        minFan = minFan,
        fanCap = fanCap,
        flowerCountsTowardMin = flowerCountsTowardMin,
        horseCount = horseCount,
        horses = horses,
        seats = seats.map { seat ->
            RecordSeat(
                seat = seat.seat,
                name = seat.name,
                seatWind = seat.seatWind,
                isDealer = seat.isDealer,
                delta = deltas.getOrElse(seat.seat) { 0 },
            )
        },
        handNumber = handNumber,
        roundWind = roundWind,
        dealer = dealer,
        baseScore = baseScore,
        rule = rule,
        mode = mode,
        time = time,
    )
}

private fun winTypeOf(winner: WinnerUi?, draw: Boolean): String {
    if (draw || winner == null) return "荒庄"
    if (winner.robKong) return "抢杠"
    // 杠上开花在引擎里是一种番型而不是独立字段，按结算时的番型清单定稿。
    val kongFlower = winner.fans.any { it.id.contains("杠上") || it.name.contains("杠上") }
    return when {
        kongFlower -> "杠上开花"
        winner.selfDraw -> "自摸"
        else -> "点炮"
    }
}

private fun FanItem.toRecordFan(minFan: Int, flowerCountsTowardMin: Boolean): RecordFan {
    // 与结算页同一套判定：无门槛时一律视为不计门槛；花牌按玩法决定。
    val counts = if (minFan <= 0) {
        false
    } else if (flowerCountsTowardMin) {
        true
    } else {
        // 只看 id：杠上开花、花龙 名字里也带「花」。
        !(id == "花牌" || id.startsWith("gd_flower"))
    }
    return RecordFan(id = id, name = name, fan = fan, count = count, countsTowardMin = counts)
}
