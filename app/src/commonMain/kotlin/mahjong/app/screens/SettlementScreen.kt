package mahjong.app.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import mahjong.app.audio.GameAudio
import mahjong.app.game.SettlementUi
import mahjong.app.game.WindNames
import mahjong.app.game.WinnerUi
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.LocalReduceMotion
import mahjong.app.theme.MjRadius
import mahjong.app.ui.DividerLine
import mahjong.app.ui.FanChip
import mahjong.app.ui.FanDetailList
import mahjong.app.ui.GhostButton
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjText
import mahjong.app.ui.MjTile
import mahjong.app.ui.PrimaryButton
import mahjong.app.ui.fanLinesOf
import mahjong.core.model.MeldType

@Composable
fun SettlementScreen(
    settlement: SettlementUi,
    onAgain: () -> Unit,
    onLobby: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val winner = settlement.winners.firstOrNull()

    val reduceMotion = LocalReduceMotion.current
    // 减弱动效时不再有入场淡入，直接以终态合成，避免首帧白屏。
    var entered by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { entered = true }
    val enter by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 240),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .graphicsLayer { alpha = enter },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MjText(
                    if (settlement.draw) "荒庄" else "本局结算",
                    type.h2,
                    colors.ink,
                )
                Spacer(Modifier.width(10.dp))
                MjText("${settlement.modeLabel} · ${settlement.subtitle}", type.caption, colors.ink3, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onLobby() },
                    contentAlignment = Alignment.Center,
                ) {
                    MjIcon(MjIconKind.CLOSE, size = 15.dp, tint = colors.ink2)
                }
            }

            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                // ---- 左：结果 + 分数 + 操作
                Column(Modifier.width(286.dp).fillMaxHeight()) {
                    ResultCard(settlement, winner)
                    Spacer(Modifier.height(8.dp))
                    ScoreTable(settlement)
                    Spacer(Modifier.height(8.dp))
                    RuleNote(settlement)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            label = "再来一局",
                            onClick = onAgain,
                            modifier = Modifier.weight(1f),
                            height = 42.dp,
                        )
                        GhostButton(
                            label = "返回大厅",
                            onClick = onLobby,
                            modifier = Modifier.weight(1f),
                            height = 42.dp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.width(12.dp))

                // ---- 右：牌面 + 番型清单
                if (winner != null) {
                    WinnerDetail(settlement, winner, Modifier.weight(1f))
                } else {
                    DrawDetail(settlement, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(settlement: SettlementUi, winner: WinnerUi?) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val meWin = settlement.winners.any { it.seat == settlement.meSeat }
    val rawDelta = settlement.deltas.getOrElse(settlement.meSeat) { 0 }
    val reduceMotion = LocalReduceMotion.current
    // 首次出现从 0 滚到终值（Compose 的 animateIntAsState 默认直接取初值，不会滚）；
    // 减少动态下 0ms 直接显示终值。
    var shown by remember { mutableStateOf(if (reduceMotion) rawDelta else 0) }
    LaunchedEffect(rawDelta) {
        shown = rawDelta
        // 与 600ms 的分数滚动同步播颗粒音，滚动结束即停。
        if (!reduceMotion && rawDelta != 0) {
            repeat(18) {
                GameAudio.playScoreTick()
                delay(33)
            }
        }
    }
    val delta by animateIntAsState(
        targetValue = shown,
        animationSpec = tween(if (reduceMotion) 0 else 600),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MjRadius.card))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(MjRadius.card))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (meWin) colors.greenSoft else colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            MjText(
                when {
                    settlement.draw -> "和"
                    meWin -> "胜"
                    else -> "负"
                },
                type.h2,
                when {
                    settlement.draw -> colors.ink2
                    meWin -> colors.green
                    else -> colors.ink3
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MjText(
                when {
                    settlement.draw -> "荒庄流局"
                    meWin -> "你胡了"
                    winner != null -> "${winner.name} 胡牌"
                    else -> settlement.title
                },
                type.title,
                colors.ink,
                maxLines = 1,
            )
            MjText(
                if (settlement.draw) {
                    settlement.reason
                } else if (winner != null) {
                    val way = if (winner.selfDraw) "自摸" else "点炮"
                    val from = winner.fromSeat?.let { " · ${seatName(settlement, it)}" } ?: ""
                    val extra = if (winner.robKong) " · 抢杠" else ""
                    "$way$from$extra"
                } else {
                    settlement.reason
                },
                type.caption.copy(fontSize = 9.sp),
                colors.ink3,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            MjText(
                if (delta > 0) "+$delta" else "$delta",
                type.num,
                when {
                    delta > 0 -> colors.green
                    delta < 0 -> colors.red
                    else -> colors.ink2
                },
            )
            MjText("本局得分", type.caption.copy(fontSize = 8.sp), colors.ink3)
        }
    }
}

@Composable
private fun ScoreTable(settlement: SettlementUi) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MjRadius.card))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(MjRadius.card))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText("分数变化", type.caption.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
            Spacer(Modifier.weight(1f))
            MjText("底分 ${settlement.baseScore}", type.caption.copy(fontSize = 8.sp), colors.ink3)
        }
        settlement.seats.forEach { seat ->
            val delta = settlement.deltas.getOrElse(seat.seat) { 0 }
            val isMe = seat.seat == settlement.meSeat
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isMe) colors.primarySoft else colors.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    MjText(
                        WindNames.getOrElse(seat.seatWind) { "东" },
                        type.caption.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                        if (isMe) colors.primaryDeep else colors.ink3,
                    )
                }
                Spacer(Modifier.width(6.dp))
                MjText(
                    if (isMe) "${seat.name}（我）" else seat.name,
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
                    if (delta > 0) "+$delta" else "$delta",
                    type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    when {
                        delta > 0 -> colors.green
                        delta < 0 -> colors.red
                        else -> colors.ink3
                    },
                )
            }
        }
    }
}

@Composable
private fun RuleNote(settlement: SettlementUi) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MjRadius.small))
            .background(colors.surface2)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (settlement.minFan > 0) {
            MjText(
                "起和线 ${settlement.minFan} 番（花牌${if (settlement.flowerCountsTowardMin) "计入" else "不计入"}）",
                type.caption.copy(fontSize = 9.sp),
                colors.ink2,
            )
        }
        val cap = settlement.fanCap
        if (cap != null) {
            MjText(
                "封顶 $cap 番（清一色、十三幺等大牌型可按规则突破，实际番数以结算结果为准）",
                type.caption.copy(fontSize = 9.sp),
                colors.ink2,
            )
        } else {
            MjText("不封顶", type.caption.copy(fontSize = 9.sp), colors.ink2)
        }
        if (settlement.horseCount > 0) {
            MjText(
                "买马 ${settlement.horseCount} 匹（庄家与中马者对赌该家本局输赢）",
                type.caption.copy(fontSize = 9.sp),
                colors.ink2,
            )
            settlement.horses.forEach { line ->
                MjText("· $line", type.caption.copy(fontSize = 8.sp), colors.ink3, maxLines = 1)
            }
        }
        if (settlement.minFan == 0 && cap == null && settlement.horseCount == 0) {
            MjText("本局无起和门槛", type.caption.copy(fontSize = 9.sp), colors.ink3)
        }
    }
}

@Composable
private fun WinnerDetail(settlement: SettlementUi, winner: WinnerUi, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val reduceMotion = LocalReduceMotion.current
    var entered by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(if (entered) 1f else 0.96f, tween(if (reduceMotion) 0 else 240))

    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(MjRadius.card))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(MjRadius.card))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(winner.name, type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            TinyTag(if (winner.selfDraw) "自摸" else "点炮")
            if (winner.robKong) {
                Spacer(Modifier.width(4.dp))
                TinyTag("抢杠")
            }
            if (winner.horseHits > 0) {
                Spacer(Modifier.width(4.dp))
                TinyTag("中马 ${winner.horseHits}")
            }
            Spacer(Modifier.weight(1f))
            FanChip(winner.totalFan)
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val meldCount = winner.melds.size
            val total = winner.handTiles.size + winner.melds.sumOf { it.tiles.size }
            val gaps = 2f * (total - 1).coerceAtLeast(0) + 6f * meldCount
            val tileW = ((maxWidth.value - gaps) / total.coerceAtLeast(1))
                .coerceIn(16f, 34f).dp
            val meldW = tileW * 0.86f
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                winner.handTiles.forEach { tile ->
                    MjTile(
                        tile = tile,
                        width = tileW,
                        height = tileW * 4f / 3f,
                        visual = if (tile == winner.winningTile) {
                            mahjong.app.ui.TileVisual.WINNABLE
                        } else {
                            mahjong.app.ui.TileVisual.NORMAL
                        },
                    )
                }
                winner.melds.forEach { meld ->
                    Spacer(Modifier.width(4.dp))
                    meld.tiles.forEach { tile ->
                        MjTile(
                            tile = tile,
                            width = meldW,
                            height = meldW * 4f / 3f,
                            faceDown = meld.type == MeldType.KONG_CONCEALED,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        DividerLine()
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText("番型明细", type.caption.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
            Spacer(Modifier.weight(1f))
            MjText("点一项展开来源", type.caption.copy(fontSize = 8.sp), colors.ink3)
        }
        Spacer(Modifier.height(2.dp))
        FanDetailList(
            lines = fanLinesOf(winner.fans, settlement.minFan, settlement.flowerCountsTowardMin),
            minFan = settlement.minFan,
            modifier = Modifier.weight(1f),
        )
        DividerLine()
        TotalRow(settlement, winner)
    }
}

@Composable
private fun TotalRow(settlement: SettlementUi, winner: WinnerUi) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val actual = settlement.deltas.getOrElse(winner.seat) { 0 }
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MjText("总番 ${winner.totalFan}", type.bodyStrong, colors.ink)
                if (settlement.minFan > 0 && winner.thresholdFan > 0) {
                    Spacer(Modifier.width(6.dp))
                    MjText(
                        "起和 ${settlement.minFan} 番 · 门槛内 ${winner.thresholdFan} 番",
                        type.caption.copy(fontSize = 8.sp),
                        colors.ink3,
                    )
                }
            }
            MjText(
                "基本分 ${winner.points} · 实得 ${if (actual > 0) "+$actual" else "$actual"}",
                type.caption.copy(fontSize = 8.sp),
                colors.ink3,
            )
        }
        MjText(
            if (actual > 0) "+$actual" else "$actual",
            type.h2,
            if (actual >= 0) colors.green else colors.red,
        )
    }
}

@Composable
private fun DrawDetail(settlement: SettlementUi, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(MjRadius.card))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(MjRadius.card))
            .padding(14.dp),
    ) {
        MjText("荒庄流局", type.h2, colors.ink)
        Spacer(Modifier.height(4.dp))
        MjText(settlement.reason, type.caption, colors.ink3)
        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(8.dp))
        MjText(
            "牌墙摸尽仍无人和牌，按当前玩法规则处理连庄与分数。",
            type.caption,
            colors.ink2,
        )
        Spacer(Modifier.weight(1f))
        MjText(
            if (settlement.modeLabel.contains("国标")) "国标：荒庄不连庄" else "广东：荒庄连庄",
            type.caption.copy(fontWeight = FontWeight.SemiBold),
            colors.ink2,
        )
    }
}

@Composable
private fun TinyTag(text: String) {
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

private fun seatName(settlement: SettlementUi, seat: Int): String =
    settlement.seats.firstOrNull { it.seat == seat }?.name ?: "玩家"
