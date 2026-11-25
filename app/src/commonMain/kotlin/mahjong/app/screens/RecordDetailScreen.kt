package mahjong.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.game.WindFullNames
import mahjong.app.game.WindNames
import mahjong.app.game.meldLabel
import mahjong.app.records.GameRecord
import mahjong.app.records.RecordDetail
import mahjong.app.records.RecordFan
import mahjong.app.records.RecordMeld
import mahjong.app.records.RecordSeat
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MjRadius
import mahjong.app.theme.MjSpace
import mahjong.app.ui.DividerLine
import mahjong.app.ui.FanChip
import mahjong.app.ui.FanDetailList
import mahjong.app.ui.FanLine
import mahjong.app.ui.MjCard
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjText
import mahjong.app.ui.MjTile
import mahjong.app.ui.TileVisual
import mahjong.core.model.MeldType

/**
 * 战绩详情：只读展示结算那一刻写入的快照，不按当前规则重算。
 * 老记录没有 [GameRecord.detail] 时给出降级提示，不留空白。
 */
@Composable
fun RecordDetailScreen(record: GameRecord, onBack: () -> Unit) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val detail = record.detail

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MjSpace.page)
                .padding(top = 2.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MjText("对局详情", type.title, colors.ink)
            }
            Box(
                Modifier
                    .size(34.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                MjIcon(MjIconKind.BACK, size = 18.dp, tint = colors.ink2)
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MjSpace.page),
        ) {
            MjText(
                listOf(record.mode, record.rule, record.time).filter { it.isNotBlank() }.joinToString(" · "),
                type.caption.copy(fontSize = 9.sp),
                colors.ink3,
            )
            Spacer(Modifier.height(8.dp))
            if (detail == null) {
                LegacyNote(record)
            } else {
                DetailBody(detail, record)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 老记录没有快照：保留摘要信息并明确提示，不做规则重算。 */
@Composable
private fun LegacyNote(record: GameRecord) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    MjCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        MjText("该局未保存详情", type.bodyStrong, colors.ink)
        Spacer(Modifier.height(4.dp))
        MjText(
            "这条记录写入时还没有详情快照，只能查看下面的摘要。",
            type.caption.copy(fontSize = 9.sp),
            colors.ink3,
        )
        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(8.dp))
        SummaryRow("结果", record.result)
        SummaryRow("玩法", "${record.mode} · ${record.rule}")
        SummaryRow("番数", if (record.fan > 0) "${record.fan} 番" else "—")
        SummaryRow(
            "本局得分",
            if (record.delta > 0) "+${record.delta}" else "${record.delta}",
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        MjText(label, type.caption.copy(fontSize = 9.sp), colors.ink3, modifier = Modifier.width(64.dp))
        MjText(value, type.caption.copy(fontSize = 9.sp), colors.ink)
    }
}

@Composable
private fun DetailBody(detail: RecordDetail, record: GameRecord) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    // 庄家按座位索引取名字：dealer 是座位号，不是风位（庄家恒为东）。
    val dealerName = detail.seats.firstOrNull { it.seat == detail.dealer }?.name.orEmpty()

    // ---- 局面
    MjCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        MjText("局面", type.caption.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
        Spacer(Modifier.height(4.dp))
        SummaryRow("规则预设", if (detail.rule.isBlank()) record.rule else detail.rule)
        SummaryRow(
            "局数",
            "${WindFullNames.getOrElse(detail.roundWind) { "东风" }} · 第 ${detail.handNumber} 局",
        )
        SummaryRow(
            "庄家",
            listOf(dealerName, "${detail.dealer + 1} 号位")
                .filter { it.isNotBlank() }
                .joinToString(" · "),
        )
        SummaryRow("时间", detail.time.ifBlank { record.time })
    }
    Spacer(Modifier.height(8.dp))

    // ---- 结果
    MjCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(
                if (detail.draw) "荒庄流局" else "${detail.winnerName} 胡牌",
                type.bodyStrong,
                if (detail.draw) colors.ink2 else colors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (!detail.draw) FanChip(detail.totalFan)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TinyPill(detail.winType.ifBlank { if (detail.draw) "荒庄" else "点炮" })
            if (detail.fromSeat != null && !detail.draw) {
                Spacer(Modifier.width(6.dp))
                MjText(
                    "放炮：${detail.seats.firstOrNull { it.seat == detail.fromSeat }?.name ?: "${detail.fromSeat + 1} 号位"}",
                    type.caption.copy(fontSize = 9.sp),
                    colors.ink3,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            val winnerDelta = detail.seats.firstOrNull { it.seat == detail.winnerSeat }?.delta ?: 0
            MjText(
                "本局 ${if (winnerDelta > 0) "+" else ""}$winnerDelta",
                type.num,
                if (winnerDelta >= 0) colors.green else colors.red,
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    // ---- 手牌 / 副露 / 花牌
    if (detail.handTiles.isNotEmpty() || detail.melds.isNotEmpty() || detail.flowers.isNotEmpty()) {
        MjCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            MjText("最终手牌", type.caption.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
            Spacer(Modifier.height(6.dp))
            HandRow(detail)
            detail.melds.forEach { meld ->
                Spacer(Modifier.height(6.dp))
                MeldRow(meld)
            }
            if (detail.flowers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MjText(
                        "花牌",
                        type.caption.copy(fontSize = 9.sp),
                        colors.ink3,
                        modifier = Modifier.width(40.dp),
                    )
                    detail.flowers.forEach { tile ->
                        MjTile(tile = tile, width = 22.dp, height = 22.dp * 4f / 3f)
                        Spacer(Modifier.width(2.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ---- 番型明细（与结算页同一组件）
    MjCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText("番型明细", type.caption.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
            Spacer(Modifier.weight(1f))
            MjText("点一项展开来源", type.caption.copy(fontSize = 8.sp), colors.ink3)
        }
        Spacer(Modifier.height(4.dp))
        if (detail.fans.isEmpty()) {
            MjText(
                if (detail.draw) "荒庄无番型" else "本局未记录番型",
                type.caption.copy(fontSize = 9.sp),
                colors.ink3,
            )
        } else {
            FanDetailList(
                lines = detail.fans.map { it.toFanLine() },
                minFan = detail.minFan,
                scrollable = false,
            )
        }
        Spacer(Modifier.height(6.dp))
        DividerLine()
        Spacer(Modifier.height(6.dp))
        for (line in ruleLines(detail)) {
            MjText(line, type.caption.copy(fontSize = 9.sp), colors.ink2)
            Spacer(Modifier.height(2.dp))
        }
    }
    Spacer(Modifier.height(8.dp))

    // ---- 四家得分
    MjCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText("分数变化", type.caption.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
            Spacer(Modifier.weight(1f))
            MjText("底分 ${detail.baseScore}", type.caption.copy(fontSize = 8.sp), colors.ink3)
        }
        Spacer(Modifier.height(2.dp))
        detail.seats.sortedBy { it.seat }.forEach { seat ->
            SeatRow(detail, seat)
        }
        if (detail.horses.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            DividerLine()
            Spacer(Modifier.height(4.dp))
            MjText(
                "买马 ${detail.horseCount} 匹",
                type.caption.copy(fontSize = 9.sp),
                colors.ink2,
            )
            detail.horses.forEach { horse ->
                MjText("· $horse", type.caption.copy(fontSize = 8.sp), colors.ink3)
            }
        }
    }
}

@Composable
private fun HandRow(detail: RecordDetail) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val count = detail.handTiles.size.coerceAtLeast(1)
        val gaps = 2f * (count - 1)
        val tileW = ((maxWidth.value - gaps) / count).coerceIn(16f, 34f).dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            detail.handTiles.forEach { tile ->
                MjTile(
                    tile = tile,
                    width = tileW,
                    height = tileW * 4f / 3f,
                    visual = if (tile == detail.winningTile) TileVisual.WINNABLE else TileVisual.NORMAL,
                )
            }
        }
    }
}

@Composable
private fun MeldRow(meld: RecordMeld) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val meldType = runCatching { MeldType.valueOf(meld.type) }.getOrDefault(MeldType.PUNG)
    Row(verticalAlignment = Alignment.CenterVertically) {
        MjText(
            meldLabel(meldType),
            type.caption.copy(fontSize = 9.sp),
            colors.ink3,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        meld.tiles.forEach { tile ->
            MjTile(
                tile = tile,
                width = 22.dp,
                height = 22.dp * 4f / 3f,
                faceDown = meldType == MeldType.KONG_CONCEALED,
            )
            Spacer(Modifier.width(2.dp))
        }
    }
}

@Composable
private fun SeatRow(detail: RecordDetail, seat: RecordSeat) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (seat.seat == detail.winnerSeat) colors.primarySoft else colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            MjText(
                WindNames.getOrElse(seat.seatWind) { "东" },
                type.caption.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                if (seat.seat == detail.winnerSeat) colors.primaryDeep else colors.ink3,
            )
        }
        Spacer(Modifier.width(6.dp))
        MjText(
            seat.name.ifBlank { "${seat.seat + 1} 号位" },
            type.caption.copy(fontSize = 9.sp),
            colors.ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (seat.isDealer) {
            MjText("庄", type.caption.copy(fontSize = 8.sp), colors.gold)
            Spacer(Modifier.width(5.dp))
        }
        MjText(
            if (seat.delta > 0) "+${seat.delta}" else "${seat.delta}",
            type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            deltaColor(seat.delta),
        )
    }
}

@Composable
private fun TinyPill(text: String) {
    val colors = LocalMjColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface2)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        MjText(
            text,
            LocalMjType.current.caption.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
            colors.ink3,
            maxLines = 1,
        )
    }
}

@Composable
private fun deltaColor(delta: Int): Color {
    val colors = LocalMjColors.current
    return when {
        delta > 0 -> colors.green
        delta < 0 -> colors.red
        else -> colors.ink3
    }
}

private fun RecordFan.toFanLine(): FanLine = FanLine(
    id = id,
    name = name,
    fan = fan,
    totalFan = fan * count,
    count = count,
    countsTowardMin = countsTowardMin,
)

/** 起和线 / 封顶 / 买马等规则说明，全部取自快照。 */
private fun ruleLines(detail: RecordDetail): List<String> {
    val lines = mutableListOf<String>()
    if (detail.minFan > 0) {
        lines += "起和线 ${detail.minFan} 番（花牌${if (detail.flowerCountsTowardMin) "计入" else "不计入"}）"
    } else {
        lines += "本局无起和门槛"
    }
    val cap = detail.fanCap
    lines += if (cap == null) {
        "不封顶"
    } else {
        "封顶 $cap 番（清一色、十三幺等大牌型可按规则突破）"
    }
    if (!detail.draw) {
        lines += "总番 ${detail.totalFan} · 基本分 ${detail.points}"
        if (detail.minFan > 0 && detail.thresholdFan > 0) {
            lines += "门槛内 ${detail.thresholdFan} 番"
        }
    }
    return lines
}
