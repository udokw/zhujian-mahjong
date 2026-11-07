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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import mahjong.app.game.SettlementUi
import mahjong.app.game.WindNames
import mahjong.app.game.WinnerUi
import mahjong.app.game.meldLabel
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.LocalReduceMotion
import mahjong.app.theme.MjRadius
import mahjong.app.ui.DividerLine
import mahjong.app.ui.FanChip
import mahjong.app.ui.GhostButton
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjText
import mahjong.app.ui.MjTile
import mahjong.app.ui.PrimaryButton
import mahjong.core.model.MeldType
import mahjong.core.score.FanItem

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
    LaunchedEffect(rawDelta) { shown = rawDelta }
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
        FanList(settlement, winner, Modifier.weight(1f))
        DividerLine()
        TotalRow(settlement, winner)
    }
}

@Composable
private fun FanList(settlement: SettlementUi, winner: WinnerUi, modifier: Modifier = Modifier) {
    val fans = winner.fans
    if (fans.size <= 6) {
        Column(modifier.verticalScroll(rememberScrollState())) {
            fans.forEach { FanRow(settlement, it) }
        }
    } else {
        val half = (fans.size + 1) / 2
        Row(modifier) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                fans.take(half).forEach { FanRow(settlement, it) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                fans.drop(half).forEach { FanRow(settlement, it) }
            }
        }
    }
}

@Composable
private fun FanRow(settlement: SettlementUi, item: FanItem) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    var expanded by remember(item.id) { mutableStateOf(false) }
    val countsTowardMin = settlement.minFan > 0 && countsTowardThreshold(settlement, item)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (item.totalFan >= 6) colors.gold else colors.lineStrong),
            )
            Spacer(Modifier.width(6.dp))
            MjText(
                if (item.count > 1) "${item.name} ×${item.count}" else item.name,
                type.caption.copy(fontSize = 10.sp),
                colors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (settlement.minFan > 0 && !countsTowardMin) {
                MjText("不计门槛", type.caption.copy(fontSize = 7.sp), colors.ink3)
                Spacer(Modifier.width(4.dp))
            }
            MjText(
                if (item.totalFan == 0) "—" else "${item.totalFan}",
                type.caption.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                if (item.fan >= 6) colors.gold else colors.ink2,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            MjText(
                fanDescription(item) + " · 计 ${item.totalFan} 番" +
                    when {
                        settlement.minFan <= 0 -> "，本玩法无起和门槛"
                        countsTowardMin -> "，计入起和门槛"
                        else -> "，不计入起和门槛"
                    },
                type.caption.copy(fontSize = 8.sp),
                colors.ink3,
            )
        }
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

private fun countsTowardThreshold(settlement: SettlementUi, item: FanItem): Boolean {
    if (settlement.flowerCountsTowardMin) return true
    // 只看 id，别用名称匹配：杠上开花/花龙 名字里也有「花」。
    return !(item.id == "花牌" || item.id.startsWith("gd_flower"))
}

private fun seatName(settlement: SettlementUi, seat: Int): String =
    settlement.seats.firstOrNull { it.seat == seat }?.name ?: "玩家"

private val fanDescriptions = mapOf(
    "大四喜" to "东、南、西、北四副风刻（含杠）",
    "小四喜" to "三副风刻加一对风牌作将",
    "大三元" to "中、发、白三副箭刻",
    "小三元" to "两副箭刻加一对箭牌作将",
    "绿一色" to "全部由二三四六八条和发财组成",
    "九莲宝灯" to "同花色 1112345678999 加任意一张",
    "四杠" to "整局共开出四副杠",
    "连七对" to "同花色七个连续对子",
    "十三幺" to "幺九字牌各一张，其中一对作将",
    "清幺九" to "全部由幺九牌组成",
    "字一色" to "全部由字牌组成",
    "四暗刻" to "四副暗刻，门前清",
    "一色双龙会" to "同花色两个老少副加五作将",
    "一色四同顺" to "同花色四副相同顺子",
    "一色四节高" to "同花色四副依次递增的刻子",
    "一色四步高" to "同花色四副依次递增一步的顺子",
    "三杠" to "整局共开出三副杠",
    "混幺九" to "全部由幺九与字牌组成",
    "七对" to "七个对子，门前清",
    "七星不靠" to "七种字牌各一张，序数牌间隔不少于三",
    "全双刻" to "全部由二四六八组成的刻子",
    "清一色" to "全部由同一花色组成",
    "一色三同顺" to "同花色三副相同顺子",
    "一色三节高" to "同花色三副依次递增的刻子",
    "全大" to "全部由七八九组成",
    "全中" to "全部由四五六组成",
    "全小" to "全部由一二三组成",
    "清龙" to "同花色一二三、四五六、七八九",
    "三色双龙会" to "两种花色各一个老少副，五作将",
    "一色三步高" to "同花色三副依次递增一步的顺子",
    "全带五" to "每副面子和将牌都含五",
    "三同刻" to "三种花色同一数字的刻子",
    "三暗刻" to "三副暗刻",
    "全不靠" to "字牌与序数牌互不相连且无重复",
    "组合龙" to "三种花色的特殊顺子组合",
    "大于五" to "全部由六七八九组成",
    "小于五" to "全部由一二三四组成",
    "三风刻" to "三副风牌刻子",
    "花龙" to "三种花色分别构成一二三、四五六、七八九",
    "推不倒" to "全部由可倒置牌组成",
    "三色三同顺" to "三种花色各一副相同顺子",
    "三色三节高" to "三种花色依次递增的刻子",
    "无番和" to "不满足任何番型但可和牌",
    "碰碰和" to "四副刻子加一对将",
    "混一色" to "一种花色加字牌",
    "五门齐" to "万条筒风箭五门齐全",
    "全求人" to "全部靠吃碰后单钓和牌",
    "双暗杠" to "两副暗杠",
    "双箭刻" to "两副箭牌刻子",
    "全带幺" to "每副面子与将牌都含幺九或字牌",
    "不求人" to "门前清且自摸",
    "双明杠" to "两副明杠",
    "和绝张" to "和牌张已现三张",
    "门前清" to "未吃碰明杠",
    "平和" to "四副顺子加序数牌将",
    "四归一" to "四张相同牌分散在面子中",
    "双同刻" to "两种花色同一数字的刻子",
    "双暗刻" to "两副暗刻",
    "暗杠" to "自己摸齐四张杠出",
    "断幺" to "全部由二至八组成",
    "一般高" to "同花色两副相同顺子",
    "喜相逢" to "两种花色相同顺子",
    "连六" to "同花色两副相连六张的顺子",
    "老少副" to "同花色一二三与七八九",
    "幺九刻" to "幺九牌的刻子",
    "明杠" to "碰后补杠或明杠他人打出的牌",
    "缺一门" to "缺少一种花色",
    "无字" to "不含字牌",
    "边张" to "和牌张为顺子的边张",
    "坎张" to "和牌张为顺子的中间张",
    "单钓将" to "单吊将牌和牌",
    "自摸" to "自己摸到和牌张",
    "花牌" to "摸到花牌补花计分",
    "鸡胡" to "最基础的胡牌，按底分计",
    "平胡" to "四副顺子加一对将",
    "碰碰胡" to "四副刻子加一对将",
    "杠上花" to "开杠后补牌自摸",
    "抢杠胡" to "抢他人补杠的牌和牌",
    "海底捞月" to "摸最后一张牌自摸",
    "天胡" to "庄家起手即和",
    "地胡" to "闲家第一巡和牌",
    "买马" to "和牌后翻马牌，按命中数加算",
)

private fun fanDescription(item: FanItem): String {
    fanDescriptions[item.name]?.let { return it }
    if (item.count > 1) return "共 ${item.count} 组，每组 ${item.fan} 番"
    return "计 ${item.totalFan} 番"
}
