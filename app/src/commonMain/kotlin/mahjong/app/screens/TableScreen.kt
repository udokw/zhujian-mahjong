package mahjong.app.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import mahjong.app.audio.GameAudio
import mahjong.app.game.Banner
import mahjong.app.game.BannerKind
import mahjong.app.game.ClaimOption
import mahjong.app.game.ClaimUi
import mahjong.app.game.DiscardPlan
import mahjong.app.game.KongOption
import mahjong.app.game.SeatUi
import mahjong.app.game.TableUi
import mahjong.app.game.TingInfo
import mahjong.app.game.WaitTile
import mahjong.app.game.WinShowUi
import mahjong.app.game.WinStage
import mahjong.app.game.WinnerUi
import mahjong.app.game.WindNames
import mahjong.app.game.meldLabel
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.LocalReduceMotion
import mahjong.app.theme.MjMotion
import mahjong.app.theme.MjRadius
import mahjong.app.ui.ActionKind
import mahjong.app.ui.Avatar
import mahjong.app.ui.DialogMessage
import mahjong.app.ui.DialogTitle
import mahjong.app.ui.GhostButton
import mahjong.app.ui.MjBackStrip
import mahjong.app.ui.MjDialog
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjText
import mahjong.app.ui.MjTile
import mahjong.app.ui.PrimaryButton
import mahjong.app.ui.ProgressBar
import mahjong.app.ui.TableActionButton
import mahjong.app.ui.TileVisual
import mahjong.app.util.Clock
import mahjong.app.util.drawTableFelt
import mahjong.core.model.Meld
import mahjong.core.model.MeldType
import mahjong.core.tile.Tiles
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun TableScreen(
    table: TableUi,
    onDiscard: (Int) -> Unit,
    onClaim: (ClaimOption, List<Int>?) -> Unit,
    onKong: (KongOption) -> Unit,
    onHu: () -> Unit,
    onExit: () -> Unit,
    autoPlaying: Boolean = false,
    onToggleAuto: (() -> Unit)? = null,
    tileScale: Float = 1f,
    myName: String = "我",
    showTing: Boolean = true,
    onToggleTing: (() -> Unit)? = null,
) {
    val colors = LocalMjColors.current
    val reduceMotion = LocalReduceMotion.current
    var selectedKey by remember { mutableStateOf<Long?>(null) }
    var selectedDrawn by remember { mutableStateOf(false) }
    var showChiSheet by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }
    var showAdvice by remember { mutableStateOf(false) }

    val hand = table.hand
    val handKey = hand.joinToString(",")
    val store = remember(table.meSeat, table.handNumber) { HandSlotStore() }
    val slots = remember(handKey) { store.sync(hand) }
    val selectedTile = slots.firstOrNull { it.key == selectedKey }?.tile
        ?: table.drawnTile?.takeIf { selectedDrawn }
    // 手牌上的听口角标：优先用 13 张时的听牌信息，14 张时用「打 X 听」方案。
    val waitTiles = table.tingInfo?.waits?.takeIf { it.isNotEmpty() }
        ?: table.plans.firstOrNull()?.waits.orEmpty()

    LaunchedEffect(handKey, table.drawnTile) {
        selectedKey = null
        selectedDrawn = false
    }
    LaunchedEffect(table.claim?.deadline) {
        if (table.claim == null) showChiSheet = false
    }
    LaunchedEffect(table.plans) {
        if (table.plans.isEmpty()) showAdvice = false
    }

    val positions = seatPositions(table.meSeat)
    val top = table.seats.getOrNull(positions[2])
    val left = table.seats.getOrNull(positions[3])
    val right = table.seats.getOrNull(positions[1])
    val advice = table.plans.firstOrNull()
    val recommend = if (table.canDiscard) advice?.discard else null

    BoxWithConstraints(Modifier.fillMaxSize().drawTableFelt(colors)) {
        val tight = maxHeight < 390.dp
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            LandscapeHud(
                table = table,
                showTing = showTing,
                onBack = { showExit = true },
                onToggleTing = onToggleTing,
                autoPlaying = autoPlaying,
                onToggleAuto = onToggleAuto,
            )

            val hasTing = showTing && hasTingContent(table) && table.winShow == null
            AnimatedVisibility(
                visible = hasTing,
                enter = fadeIn(tween(if (reduceMotion) 0 else MjMotion.small)),
                exit = fadeOut(tween(if (reduceMotion) 0 else MjMotion.small)),
            ) {
                TingBar(
                    table = table,
                    onExpand = { showAdvice = !showAdvice },
                    expanded = showAdvice,
                )
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                SideSeat(
                    seat = left,
                    table = table,
                    isCurrent = isCurrentSeat(table, left),
                    width = if (tight) 70.dp else 80.dp,
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        TopSeat(
                            seat = top,
                            table = table,
                            isCurrent = isCurrentSeat(table, top),
                            height = if (tight) 36.dp else 44.dp,
                        )
                        CompactRiver(
                            table = table,
                            top = top,
                            left = left,
                            right = right,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (showAdvice) {
                        AdvicePanel(
                            table = table,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 4.dp, top = 2.dp),
                            onPick = { tile ->
                                selectedKey = slots.firstOrNull { it.tile == tile }?.key
                                selectedDrawn = false
                                showAdvice = false
                            },
                            onClose = { showAdvice = false },
                        )
                    }
                }
                SideSeat(
                    seat = right,
                    table = table,
                    isCurrent = isCurrentSeat(table, right),
                    width = if (tight) 70.dp else 80.dp,
                )
            }

            BottomBand(
                table = table,
                slots = slots,
                selectedKey = selectedKey,
                selectedDrawn = selectedDrawn,
                recommend = recommend,
                drawn = table.drawnTile,
                waits = waitTiles,
                selectedTile = selectedTile,
                tight = tight,
                tileScale = tileScale,
                reduceMotion = reduceMotion,
                onSelect = { slot ->
                    if (selectedKey == slot.key) {
                        onDiscard(slot.tile)
                        selectedKey = null
                    } else {
                        GameAudio.playPick()
                        selectedKey = slot.key
                        selectedDrawn = false
                    }
                },
                onSelectDrawn = {
                    val drawn = table.drawnTile
                    if (drawn != null) {
                        if (selectedDrawn) {
                            onDiscard(drawn)
                            selectedDrawn = false
                        } else {
                            GameAudio.playPick()
                            selectedDrawn = true
                            selectedKey = null
                        }
                    }
                },
                onDragDiscard = { tile ->
                    selectedKey = null
                    selectedDrawn = false
                    onDiscard(tile)
                },
                onClaim = onClaim,
                onKong = onKong,
                onHu = onHu,
                onChiMore = { showChiSheet = true },
                onConfirmDiscard = { tile ->
                    selectedKey = null
                    selectedDrawn = false
                    onDiscard(tile)
                },
            )
        }

        BannerOverlay(
            if (table.winShow == null) table.banner else null,
            Modifier.align(Alignment.Center),
        )

        table.winShow?.let { show ->
            WinSequenceOverlay(
                show = show,
                modeLabel = table.modeLabel,
                modifier = Modifier.fillMaxSize().zIndex(9f),
            )
        }

        if (showChiSheet) {
            val chi = table.claim?.options?.filterIsInstance<ClaimOption.Chi>()?.firstOrNull()
            if (chi != null) {
                ChiSheet(
                    options = chi.choices,
                    claimed = table.claim?.tile,
                    onDismiss = { showChiSheet = false },
                    onPick = { tiles ->
                        showChiSheet = false
                        onClaim(ClaimOption.Chi(chi.choices), tiles)
                    },
                )
            }
        }

        if (showExit) {
            ExitDialog(
                onContinue = { showExit = false },
                onExit = {
                    showExit = false
                    onExit()
                },
            )
        }
    }
}

// ------------------------------------------------------------ 顶部信息

@Composable
private fun LandscapeHud(
    table: TableUi,
    showTing: Boolean,
    onBack: () -> Unit,
    onToggleTing: (() -> Unit)?,
    autoPlaying: Boolean,
    onToggleAuto: (() -> Unit)?,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.30f),
                    1f to Color.Transparent,
                ),
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            MjIcon(MjIconKind.BACK, size = 16.dp, tint = colors.onTable)
        }
        Spacer(Modifier.width(6.dp))
        MjText(
            "${table.modeLabel} · ${WindNames.getOrElse(table.roundWind) { "东" }}风 · 第 ${table.handNumber} 局",
            type.caption.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
            colors.onTable,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        MjText(
            "余 ${table.wallRemaining}",
            type.caption.copy(fontSize = 10.sp),
            colors.onTable2,
            maxLines = 1,
        )
        if (onToggleTing != null) {
            Spacer(Modifier.width(8.dp))
            HudChip(
                label = "听牌提示",
                active = showTing,
                onClick = onToggleTing,
            )
        }
        if (onToggleAuto != null) {
            Spacer(Modifier.width(6.dp))
            HudChip(
                label = "托管",
                active = autoPlaying,
                onClick = onToggleAuto,
            )
        }
    }
}

@Composable
private fun HudChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) colors.tableGold else Color.Transparent)
            .border(1.dp, colors.tableLine, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        MjText(
            label,
            type.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
            if (active) colors.tableGoldInk else colors.onTable2,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------ 听牌提示条

private fun hasTingContent(table: TableUi): Boolean {
    if (!table.hintsAvailable) return false
    if (table.plans.isNotEmpty()) return true
    val ting = table.tingInfo
    if (ting != null && ting.waits.isNotEmpty()) return true
    return table.canDiscard && table.plans.isEmpty()
}

@Composable
private fun TingBar(table: TableUi, expanded: Boolean, onExpand: () -> Unit) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val plan = table.plans.firstOrNull()
    val ting = table.tingInfo
    val minFan = table.minFan

    val accent = when {
        plan != null -> if (plan.meetsMin) colors.tableGold else colors.tableGold.copy(alpha = 0.55f)
        ting != null && ting.waits.isNotEmpty() -> if (ting.meetsMin) colors.tableGold else colors.tableGold.copy(alpha = 0.55f)
        else -> colors.onTable2
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable { onExpand() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MjText(
            "听",
            type.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
            accent,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        MjText(
            text = tingLine(table, plan, ting, minFan),
            style = type.caption.copy(fontSize = 10.sp),
            color = colors.onTable,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        MjText(
            if (expanded) "收起" else "详情",
            type.caption.copy(fontSize = 9.sp),
            colors.onTable2,
            maxLines = 1,
        )
    }
}

private fun tingLine(
    table: TableUi,
    plan: DiscardPlan?,
    ting: TingInfo?,
    minFan: Int,
): String {
    if (plan != null) {
        val waits = waitSummary(plan.waits)
        val fan = if (plan.bestFan > 0) " · 预计 ${plan.bestFan} 番" else ""
        val warn = if (!plan.meetsMin && minFan > 0) " · 不足 $minFan 番" else ""
        return "建议打 ${Tiles.name(plan.discard)} · 听 $waits$fan$warn"
    }
    if (ting != null && ting.waits.isNotEmpty()) {
        val fan = if (ting.bestFan > 0) " · 预计 ${ting.bestFan} 番" else ""
        val warn = if (!ting.meetsMin && minFan > 0) " · 听牌但不足 $minFan 番" else ""
        return "听 ${waitSummary(ting.waits)}$fan$warn"
    }
    if (table.canDiscard) {
        return "尚未听牌 · 继续摸打"
    }
    return "等待其他玩家…"
}

private fun waitSummary(waits: List<WaitTile>): String {
    if (waits.isEmpty()) return "—"
    return waits.take(6).joinToString(" ") { "${Tiles.name(it.tile)}×${it.remaining}" } +
        if (waits.size > 6) " +${waits.size - 6}" else ""
}

// ------------------------------------------------------------ 对手

@Composable
private fun TopSeat(seat: SeatUi?, table: TableUi, isCurrent: Boolean, height: Dp) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    if (seat == null) {
        Spacer(Modifier.height(height))
        return
    }
    Box(
        Modifier.fillMaxWidth().height(height).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.table3.copy(alpha = if (isCurrent) 0.62f else 0.40f))
            .border(
                1.dp,
                if (isCurrent) colors.tableGold.copy(alpha = 0.80f) else colors.tableLine,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Avatar(seat.name, size = 24.dp, dim = !seat.isAI)
        Spacer(Modifier.width(6.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MjText(seat.name, type.caption.copy(fontWeight = FontWeight.SemiBold), colors.onTable, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                WindDot(seat.seatWind, isCurrent)
                Spacer(Modifier.width(4.dp))
                MjText("${seat.score}", type.caption.copy(fontSize = 9.sp), colors.onTable2, maxLines = 1)
            }
            if (seat.melds.isNotEmpty()) {
                MjText(
                    seat.melds.joinToString(" ") { meldLabel(it.type) },
                    type.caption.copy(fontSize = 8.sp),
                    colors.tableGold,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            repeat(seat.handCount.coerceAtMost(14)) {
                MjBackStrip(6.dp, 18.dp)
            }
        }
    }
    }
}

@Composable
private fun SideSeat(seat: SeatUi?, table: TableUi, isCurrent: Boolean, width: Dp) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    if (seat == null) {
        Spacer(Modifier.width(width))
        return
    }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.table3.copy(alpha = if (isCurrent) 0.62f else 0.40f))
                .border(
                    1.dp,
                    if (isCurrent) colors.tableGold.copy(alpha = 0.80f) else colors.tableLine,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Avatar(seat.name, size = 26.dp, dim = !seat.isAI)
        Spacer(Modifier.height(3.dp))
        MjText(seat.name, type.caption.copy(fontSize = 10.sp), colors.onTable, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WindDot(seat.seatWind, isCurrent, small = true)
            Spacer(Modifier.width(3.dp))
            MjText("${seat.score}", type.caption.copy(fontSize = 9.sp), colors.onTable2, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(seat.handCount.coerceAtMost(14)) {
                MjBackStrip(5.dp, 11.dp)
            }
        }
        if (seat.melds.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            MjText(
                seat.melds.joinToString("") { meldLabel(it.type) },
                type.caption.copy(fontSize = 8.sp),
                colors.tableGold,
                maxLines = 1,
            )
        }
        }
    }
}

@Composable
private fun WindDot(wind: Int, isCurrent: Boolean, small: Boolean = false) {
    val colors = LocalMjColors.current
    val size = if (small) 12.dp else 14.dp
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isCurrent) colors.tableGold else colors.onTable.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        MjText(
            WindNames.getOrElse(wind) { "东" },
            LocalMjType.current.caption.copy(fontSize = if (small) 7.sp else 8.sp, fontWeight = FontWeight.SemiBold),
            if (isCurrent) colors.tableGoldInk else colors.onTable2,
            maxLines = 1,
        )
    }
}

private fun isCurrentSeat(table: TableUi, seat: SeatUi?): Boolean {
    if (seat == null) return false
    return table.currentPlayer == seat.seat || table.banner?.seat == seat.seat
}

// ------------------------------------------------------------ 牌河

@Composable
private fun CompactRiver(
    table: TableUi,
    top: SeatUi?,
    left: SeatUi?,
    right: SeatUi?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.padding(horizontal = 2.dp)) {
        val cell = minOf(
            20.dp,
            ((maxHeight.value - 8f) / 6f * 0.75f).dp,
            ((maxWidth.value - 92f) / 12f).dp,
        ).coerceAtLeast(9.dp)
        val gap = 1.dp
        val winTiles = if (table.claim?.options?.contains(ClaimOption.Hu) == true &&
            table.claim.fromSeat != null && table.claim.fromSeat == table.lastDiscardSeat
        ) {
            setOf(table.lastDiscardSeat!! to (table.lastDiscardTile ?: -1))
        } else {
            emptySet()
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RiverGrid(
                tiles = top?.discards ?: emptyList(),
                columns = 12,
                cell = cell,
                gap = gap,
                maxRows = 2,
                alignBottom = true,
                highlight = if (top != null && top.seat == table.lastDiscardSeat) table.lastDiscardTile else null,
                winning = winTiles.any { it.first == top?.seat },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RiverGrid(
                    tiles = left?.discards ?: emptyList(),
                    columns = 8,
                    cell = cell,
                    gap = gap,
                    maxRows = 2,
                    alignBottom = false,
                    highlight = if (left != null && left.seat == table.lastDiscardSeat) table.lastDiscardTile else null,
                    winning = winTiles.any { it.first == left?.seat },
                )
                CenterInfo(table)
                RiverGrid(
                    tiles = right?.discards ?: emptyList(),
                    columns = 8,
                    cell = cell,
                    gap = gap,
                    maxRows = 2,
                    alignBottom = false,
                    highlight = if (right != null && right.seat == table.lastDiscardSeat) table.lastDiscardTile else null,
                    winning = winTiles.any { it.first == right?.seat },
                )
            }
            RiverGrid(
                tiles = table.seats.getOrNull(table.meSeat)?.discards ?: emptyList(),
                columns = 12,
                cell = cell,
                gap = gap,
                maxRows = 2,
                alignBottom = false,
                highlight = if (table.meSeat == table.lastDiscardSeat) table.lastDiscardTile else null,
                winning = false,
            )
        }
    }
}

@Composable
private fun RiverGrid(
    tiles: List<Int>,
    columns: Int,
    cell: Dp,
    gap: Dp,
    maxRows: Int,
    alignBottom: Boolean,
    highlight: Int?,
    winning: Boolean,
) {
    val reduceMotion = LocalReduceMotion.current
    val rowHeight = cell * 4f / 3f + gap
    Column(
        modifier = Modifier.height(rowHeight * maxRows),
        verticalArrangement = if (alignBottom) Arrangement.Bottom else Arrangement.Top,
    ) {
        if (tiles.isEmpty()) return@Column
        val shown = if (tiles.size > maxRows * columns) tiles.takeLast(maxRows * columns) else tiles
        shown.chunked(columns).forEachIndexed { rowIndex, rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                rowTiles.forEachIndexed { colIndex, tile ->
                    val absolute = if (tiles.size > maxRows * columns) {
                        tiles.size - shown.size + rowIndex * columns + colIndex
                    } else {
                        rowIndex * columns + colIndex
                    }
                    val isLast = absolute == tiles.lastIndex
                    val visual = when {
                        isLast && winning -> TileVisual.WINNABLE
                        isLast && highlight == tile && tile == tiles.last() -> TileVisual.JUST_PLAYED
                        else -> TileVisual.NORMAL
                    }
                    if (isLast) {
                        key(tiles.size) {
                            var appear by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { appear = true }
                            val a by animateFloatAsState(
                                targetValue = if (appear) 1f else 0.35f,
                                animationSpec = tween(if (reduceMotion) 0 else MjMotion.small),
                            )
                            val s by animateFloatAsState(
                                targetValue = if (appear) 1f else 0.78f,
                                animationSpec = tween(if (reduceMotion) 0 else MjMotion.small),
                            )
                            Box(Modifier.graphicsLayer { alpha = a; scaleX = s; scaleY = s }) {
                                MjTile(tile = tile, width = cell, visual = visual)
                            }
                        }
                    } else {
                        MjTile(tile = tile, width = cell, visual = visual)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterInfo(table: TableUi) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(
        modifier = Modifier
            .width(86.dp)
            .drawBehind {
                val r = minOf(size.width, size.height) * 0.52f
                drawCircle(colors.onTable.copy(alpha = 0.05f), radius = r)
                drawCircle(colors.tableLine, radius = r, style = Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MjText("余牌", type.caption.copy(fontSize = 8.sp), colors.onTable2)
        MjText("${table.wallRemaining}", type.h2, colors.onTable)
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.onTable.copy(alpha = 0.10f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MjText(
                "${WindNames[table.roundWind]}风",
                type.caption.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold),
                colors.tableGold,
                maxLines = 1,
            )
            Spacer(Modifier.width(3.dp))
            MjText(
                "第${table.handNumber}局",
                type.caption.copy(fontSize = 8.sp),
                colors.onTable2,
                maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------ 底部：操作 / 副露 / 手牌

@Composable
private fun BottomBand(
    table: TableUi,
    slots: List<HandSlot>,
    selectedKey: Long?,
    selectedDrawn: Boolean,
    recommend: Int?,
    drawn: Int?,
    waits: List<WaitTile>,
    selectedTile: Int?,
    tight: Boolean,
    tileScale: Float,
    reduceMotion: Boolean,
    onSelect: (HandSlot) -> Unit,
    onSelectDrawn: () -> Unit,
    onDragDiscard: (Int) -> Unit,
    onClaim: (ClaimOption, List<Int>?) -> Unit,
    onKong: (KongOption) -> Unit,
    onHu: () -> Unit,
    onChiMore: () -> Unit,
    onConfirmDiscard: (Int) -> Unit,
) {
    val colors = LocalMjColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.26f),
                ),
            ),
    ) {
        ActionRow(
            table = table,
            selectedTile = selectedTile,
            onClaim = onClaim,
            onKong = onKong,
            onHu = onHu,
            onChiMore = onChiMore,
            onConfirmDiscard = onConfirmDiscard,
        )
        MeldsStrip(table)
        HandRow(
            slots = slots,
            drawn = drawn,
            selectedKey = selectedKey,
            selectedDrawn = selectedDrawn,
            recommend = recommend,
            waits = waits,
            canHu = table.turnOptions.canHu,
            tight = tight,
            tileScale = tileScale,
            reduceMotion = reduceMotion,
            onSelect = onSelect,
            onSelectDrawn = onSelectDrawn,
            onDragDiscard = onDragDiscard,
        )
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun ActionRow(
    table: TableUi,
    selectedTile: Int?,
    onClaim: (ClaimOption, List<Int>?) -> Unit,
    onKong: (KongOption) -> Unit,
    onHu: () -> Unit,
    onChiMore: () -> Unit,
    onConfirmDiscard: (Int) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val claim = table.claim
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 单手操作区统一贴右下，缩短拇指移动距离。
        horizontalArrangement = Arrangement.End,
    ) {
        if (claim != null) {
            ClaimCountdown(claim, Modifier.width(78.dp))
            Spacer(Modifier.width(8.dp))
            MjText(
                claimSummary(table, claim),
                type.caption.copy(fontSize = 10.sp),
                if (claim.options.contains(ClaimOption.Hu)) colors.tableGold else colors.onTable2,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                claim.options.forEach { option ->
                    when (option) {
                        is ClaimOption.Chi -> TableActionButton(
                            label = "吃",
                            height = 42.dp,
                            onClick = {
                                if (option.choices.size == 1) {
                                    onClaim(ClaimOption.Chi(option.choices), option.choices.first())
                                } else {
                                    onChiMore()
                                }
                            },
                        )

                        ClaimOption.Pass -> TableActionButton(
                            label = "过",
                            height = 42.dp,
                            kind = ActionKind.PASS,
                            modifier = Modifier.widthIn(min = 58.dp),
                            onClick = { onClaim(ClaimOption.Pass, null) },
                        )

                        ClaimOption.Hu -> TableActionButton(
                            label = table.huFan?.let { "胡 · ${it}番" } ?: "胡",
                            height = 46.dp,
                            kind = ActionKind.HU,
                            modifier = Modifier.widthIn(min = 112.dp),
                            onClick = { onClaim(ClaimOption.Hu, null) },
                        )

                        else -> TableActionButton(
                            label = claimLabel(option),
                            height = 42.dp,
                            onClick = { onClaim(option, null) },
                        )
                    }
                }
            }
        } else {
            MjText(
                if (table.turnOptions.canHu && !table.huMeetsMin) {
                    "听牌但仅 ${table.huFan ?: 0} 番 · 不足 ${table.minFan} 番，不能胡"
                } else {
                    table.hint
                },
                type.caption.copy(fontSize = 10.sp),
                if (table.turnOptions.canHu && !table.huMeetsMin) colors.tableGold else colors.onTable2,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (table.canDiscard && selectedTile != null) {
                    TableActionButton(
                        label = "打出 ${Tiles.name(selectedTile)}",
                        height = 42.dp,
                        onClick = { onConfirmDiscard(selectedTile) },
                    )
                }
                if (table.turnOptions.kongs.isNotEmpty()) {
                    // 多杠时直接平铺选项，不弹窗打断。
                    table.turnOptions.kongs.forEach { option ->
                        TableActionButton(
                            label = if (table.turnOptions.kongs.size == 1) "杠" else option.label,
                            height = 42.dp,
                            onClick = { onKong(option) },
                        )
                    }
                }
                if (table.turnOptions.canHu && table.huMeetsMin) {
                    TableActionButton(
                        label = table.huFan?.let { "胡 · ${it}番" } ?: "胡",
                        height = 46.dp,
                        kind = ActionKind.HU,
                        modifier = Modifier.widthIn(min = 112.dp),
                        onClick = onHu,
                    )
                }
            }
        }
    }
}

private fun claimSummary(table: TableUi, claim: ClaimUi): String {
    val from = table.seats.firstOrNull { it.seat == claim.fromSeat }?.name ?: "对手"
    val tile = claim.tile?.let { Tiles.name(it) } ?: "牌"
    val fan = table.huFan
    return if (claim.options.contains(ClaimOption.Hu) && fan != null) {
        "$from 打出 $tile · 可胡 $fan 番"
    } else {
        "$from 打出 $tile"
    }
}

@Composable
private fun ClaimCountdown(claim: ClaimUi, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    var now by remember { mutableStateOf(Clock.now()) }
    LaunchedEffect(claim.deadline) {
        while (true) {
            now = Clock.now()
            delay(90)
            if (now >= claim.deadline) break
        }
    }
    val remaining = (claim.deadline - now).coerceAtLeast(0L)
    // 响应倒计时：最后 5 秒每秒一声，提示玩家尽快决策。
    LaunchedEffect(claim.deadline) {
        while (true) {
            val left = claim.deadline - Clock.now()
            if (left <= 0L) break
            if (left <= 5_000L) GameAudio.playCountdown()
            delay(1_000L)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ProgressBar(
            fraction = remaining.toFloat() / claim.totalMs.toFloat(),
            modifier = Modifier.weight(1f),
            color = if (remaining < 2500) colors.tableGold else colors.onTable2,
        )
        Spacer(Modifier.width(5.dp))
        MjText(
            "${(remaining / 1000) + 1}s",
            type.caption.copy(fontSize = 9.sp),
            colors.onTable2,
            maxLines = 1,
        )
    }
}

@Composable
private fun MeldsStrip(table: TableUi) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val hasContent = table.melds.isNotEmpty() || table.flowers.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(15.dp).clip(RoundedCornerShape(2.dp)).background(colors.tableGold.copy(alpha = if (hasContent) 0.9f else 0.25f)))
        Spacer(Modifier.width(6.dp))
        if (hasContent) {
            MjText("副露", type.caption.copy(fontSize = 8.sp), colors.onTable2, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                table.melds.forEach { meld -> MeldGroup(meld, showName = table.melds.size < 3) }
                if (table.flowers.isNotEmpty()) {
                    Box(Modifier.width(1.dp).height(14.dp).background(colors.tableLine))
                    MjText("花", type.caption.copy(fontSize = 8.sp), colors.tableGold, maxLines = 1)
                    table.flowers.forEach { tile ->
                        MjTile(tile = tile, width = 14.dp, height = 18.dp)
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f).height(1.dp).background(colors.tableLine.copy(alpha = 0.4f)))
            Spacer(Modifier.width(6.dp))
            MjText("手牌", type.caption.copy(fontSize = 8.sp), colors.onTable2, maxLines = 1)
        }
    }
}

@Composable
private fun MeldGroup(meld: Meld, showName: Boolean) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(-4.dp)) {
            meld.tiles.forEach { tile ->
                MjTile(
                    tile = tile,
                    width = 15.dp,
                    height = 20.dp,
                    faceDown = meld.type == MeldType.KONG_CONCEALED,
                )
            }
        }
        if (showName) {
            Spacer(Modifier.width(3.dp))
            MjText(
                meldLabel(meld.type),
                type.caption.copy(fontSize = 8.sp),
                colors.onTable2,
                maxLines = 1,
            )
        }
    }
}

/** 手牌槽位：key 稳定，保证自动理牌时是“移动”而不是“重建”。 */
private data class HandSlot(val key: Long, val tile: Int)

private class HandSlotStore {
    private var slots: List<HandSlot> = emptyList()
    private var next = 0L

    fun sync(hand: List<Int>): List<HandSlot> {
        val used = BooleanArray(slots.size)
        val out = hand.map { tile ->
            val index = slots.indices.firstOrNull { !used[it] && slots[it].tile == tile }
            if (index != null) {
                used[index] = true
                slots[index]
            } else {
                HandSlot(next++, tile)
            }
        }
        slots = out
        return out
    }
}

@Composable
private fun HandRow(
    slots: List<HandSlot>,
    drawn: Int?,
    selectedKey: Long?,
    selectedDrawn: Boolean,
    recommend: Int?,
    waits: List<WaitTile>,
    canHu: Boolean,
    tight: Boolean,
    tileScale: Float,
    reduceMotion: Boolean,
    onSelect: (HandSlot) -> Unit,
    onSelectDrawn: () -> Unit,
    onDragDiscard: (Int) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        val count = slots.size.coerceAtLeast(1)
        val gap = 1.dp
        val drawnGap = if (drawn != null) 12.dp else 0.dp
        val drawnFactor = 1.12f
        val maxTile = if (tight) 34.dp else 46.dp
        // 听口角标贴在牌带右侧；先预留宽度，避免压住手牌。
        val shownWaits = waits.take(if (tight) 2 else 4)
        val chipW = if (tight) 17.dp else 21.dp
        val stripW = if (shownWaits.isEmpty()) 0.dp
        else 19.dp + (chipW + 2.dp) * shownWaits.size + 4.dp
        val contentW = (maxWidth - stripW).coerceAtLeast(120.dp)
        val widthForTiles = contentW - drawnGap - gap * (count - 1)
        val tileW = (widthForTiles / (count + (if (drawn != null) drawnFactor else 0f)))
            .coerceIn(11.dp, maxTile * tileScale)
        val tileH = tileW * 4f / 3f
        val groupW = tileW * count + gap * (count - 1) + drawnGap +
            (if (drawn != null) tileW * drawnFactor else 0.dp)
        val startX = ((contentW - groupW) / 2).coerceAtLeast(0.dp)
        val lastRecommendIndex = if (recommend != null) {
            slots.indexOfLast { it.tile == recommend }
        } else {
            -1
        }

        var firstDeal by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(420)
            firstDeal = false
        }

        Box(Modifier.fillMaxWidth().height(tileH + 10.dp)) {
            slots.forEachIndexed { index, slot ->
                key(slot.key) {
                    val selected = selectedKey == slot.key
                    val targetX = startX + (tileW + gap) * index
                    val x by animateDpAsState(
                        targetValue = targetX,
                        animationSpec = tween(
                            durationMillis = if (reduceMotion) 0 else MjMotion.small,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                    val lift by animateDpAsState(
                        targetValue = if (selected) 7.dp else 0.dp,
                        animationSpec = tween(if (reduceMotion) 0 else MjMotion.micro),
                    )
                    var appeared by remember(slot.key) { mutableStateOf(false) }
                    LaunchedEffect(slot.key) {
                        if (firstDeal) delay(index * 16L)
                        appeared = true
                    }
                    val alpha by animateFloatAsState(
                        targetValue = if (appeared) 1f else 0f,
                        animationSpec = tween(if (reduceMotion) 0 else MjMotion.small),
                    )
                    var dragY by remember(slot.key) { mutableStateOf(0f) }
                    val visual = when {
                        selected -> TileVisual.SELECTED
                        index == lastRecommendIndex -> TileVisual.RECOMMENDED
                        else -> TileVisual.NORMAL
                    }
                    Box(
                        Modifier
                            .offset(x = x, y = 5.dp - lift)
                            .zIndex(if (selected) 3f else 1f)
                            .graphicsLayer {
                                this.alpha = alpha
                                translationY = (1f - alpha) * 26f + dragY * 0.35f
                            }
                            .pointerInput(slot.key) {
                                detectTapGestures(onTap = { onSelect(slot) })
                            }
                            .pointerInput(slot.key) {
                                var total = 0f
                                detectDragGestures(
                                    onDragStart = { total = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        total += amount.y
                                        dragY = total
                                    },
                                    onDragEnd = {
                                        val threshold = -28.dp.toPx()
                                        if (total <= threshold) onDragDiscard(slot.tile)
                                        dragY = 0f
                                    },
                                    onDragCancel = { dragY = 0f },
                                )
                            },
                    ) {
                        MjTile(tile = slot.tile, width = tileW, height = tileH, visual = visual)
                    }
                }
            }

            if (drawn != null) {
                val drawnW = tileW * drawnFactor
                val drawnH = drawnW * 4f / 3f
                val drawnX = startX + (tileW + gap) * (count - 1) + tileW + drawnGap
                var entered by remember(drawn) { mutableStateOf(false) }
                LaunchedEffect(drawn) { entered = true }
                val a by animateFloatAsState(
                    targetValue = if (entered) 1f else 0f,
                    animationSpec = tween(if (reduceMotion) 0 else MjMotion.small),
                )
                val lift by animateDpAsState(
                    targetValue = if (selectedDrawn) 7.dp else 0.dp,
                    animationSpec = tween(if (reduceMotion) 0 else MjMotion.micro),
                )
                Box(
                    Modifier
                        .offset(x = drawnX, y = 5.dp + (tileH - drawnH) - lift)
                        .zIndex(if (selectedDrawn) 3f else 2f)
                        .graphicsLayer {
                            alpha = a
                            translationX = (1f - a) * 22f
                        }
                        .pointerInput(drawn) {
                            detectTapGestures(onTap = { onSelectDrawn() })
                        },
                ) {
                    MjTile(
                        tile = drawn,
                        width = drawnW,
                        height = drawnH,
                        visual = when {
                            selectedDrawn -> TileVisual.SELECTED
                            canHu -> TileVisual.WINNABLE
                            else -> TileVisual.NORMAL
                        },
                    )
                }
            }

            if (shownWaits.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.tableGold)
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    ) {
                        MjText(
                            "听",
                            type.caption.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            colors.tableGoldInk,
                        )
                    }
                    shownWaits.forEach { wait ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .border(1.dp, colors.tableGold, RoundedCornerShape(4.dp))
                                    .padding(1.dp),
                            ) {
                                MjTile(
                                    tile = wait.tile,
                                    width = chipW,
                                    visual = if (wait.meetsMin) TileVisual.WINNABLE else TileVisual.NORMAL,
                                )
                            }
                            MjText(
                                "×${wait.remaining}",
                                type.caption.copy(fontSize = 7.sp),
                                if (wait.meetsMin) colors.tableGold else colors.onTable2,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ 建议详情

@Composable
private fun AdvicePanel(
    table: TableUi,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val plans = table.plans
    val ting = table.tingInfo
    Column(
        modifier
            .width(304.dp)
            .heightIn(max = 158.dp)
            .clip(RoundedCornerShape(MjRadius.small))
            .background(colors.surface.copy(alpha = 0.97f))
            .border(1.dp, colors.line, RoundedCornerShape(MjRadius.small))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(
                if (plans.isNotEmpty()) "出牌建议" else "听口",
                type.label.copy(fontWeight = FontWeight.SemiBold),
                colors.ink,
            )
            Spacer(Modifier.weight(1f))
            MjText("关闭", type.caption.copy(fontSize = 9.sp), colors.ink3, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (plans.isNotEmpty()) {
                plans.take(10).forEachIndexed { index, plan ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (index == 0) colors.primary.copy(alpha = 0.07f) else Color.Transparent)
                            .clickable { onPick(plan.discard) }
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MjText(
                            "打 ${Tiles.name(plan.discard)}",
                            type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            if (index == 0) colors.primary else colors.ink2,
                            maxLines = 1,
                            modifier = Modifier.width(56.dp),
                        )
                        MjText(
                            waitSummary(plan.waits),
                            type.caption.copy(fontSize = 9.sp),
                            colors.ink2,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        MjText(
                            if (plan.bestFan > 0) "${plan.bestFan}番" else "",
                            type.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                            colors.ink3,
                            maxLines = 1,
                        )
                    }
                }
            } else if (ting != null && ting.waits.isNotEmpty()) {
                ting.waits.forEach { wait ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MjText(
                            Tiles.name(wait.tile),
                            type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            colors.ink,
                            modifier = Modifier.width(48.dp),
                        )
                        MjText(
                            "剩 ${wait.remaining} 张",
                            type.caption.copy(fontSize = 9.sp),
                            colors.ink2,
                            modifier = Modifier.weight(1f),
                        )
                        MjText(
                            if (wait.fan > 0) "预计 ${wait.fan} 番" else "",
                            type.caption.copy(fontSize = 9.sp),
                            if (wait.meetsMin) colors.ink3 else colors.gold,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                MjText(
                    if (table.canDiscard) "当前手牌打一张后尚未听牌，建议先做向听。" else "等待其他玩家出牌。",
                    type.caption.copy(fontSize = 9.sp),
                    colors.ink3,
                )
            }
            if (table.modeLabel.contains("国标") && (plans.isNotEmpty() || (ting?.waits?.isNotEmpty() == true)) &&
                !(plans.firstOrNull()?.meetsMin ?: ting?.meetsMin ?: true)
            ) {
                Spacer(Modifier.height(4.dp))
                MjText(
                    "国标 8 番起和：当前听口最多 ${plans.firstOrNull()?.bestFan ?: ting?.bestFan ?: 0} 番，和牌仍需补番。",
                    type.caption.copy(fontSize = 9.sp),
                    colors.gold,
                )
            }
            Spacer(Modifier.height(2.dp))
            MjText(
                if (plans.isNotEmpty()) "点一行可直接提牌，再点一次打出。" else "剩余张数按场上可见牌计算。",
                type.caption.copy(fontSize = 8.sp),
                colors.ink3,
            )
        }
    }
}

// ------------------------------------------------------------ 弹层

// ------------------------------------------------------------ 胡牌演出

@Composable
private fun WinSequenceOverlay(
    show: WinShowUi,
    modeLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val reduce = LocalReduceMotion.current
    val winner = show.winner
    val dimmed = show.index > 0 || show.stage == WinStage.DIM || show.stage == WinStage.FAN
    val dim by animateFloatAsState(
        targetValue = if (dimmed) 0.32f else 0f,
        animationSpec = tween(if (reduce) 0 else MjMotion.medium),
    )
    val frame by animateFloatAsState(
        targetValue = if (show.stage != WinStage.REVEAL || reduce) 1f else 0f,
        animationSpec = tween(if (reduce) 0 else MjMotion.small),
    )
    val panelHeight by animateDpAsState(
        targetValue = if (show.stage == WinStage.FAN) {
            (58 + winner.fans.size.coerceAtLeast(1) * 18).coerceAtMost(152).dp
        } else {
            0.dp
        },
        animationSpec = tween(if (reduce) 0 else MjMotion.medium),
    )
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val cardAlpha by animateFloatAsState(
        targetValue = if (entered || reduce) 1f else 0f,
        animationSpec = tween(if (reduce) 0 else MjMotion.small),
    )
    val cardScale by animateFloatAsState(
        targetValue = if (entered || reduce) 1f else 0.96f,
        animationSpec = tween(if (reduce) 0 else MjMotion.small),
    )
    Box(
        modifier.pointerInput(Unit) {
            // 演出期间吞掉所有触摸，避免误操作牌桌。
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        Column(
            Modifier
                .fillMaxWidth(0.74f)
                .widthIn(max = 640.dp)
                .graphicsLayer {
                    alpha = cardAlpha
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MjText(
                    winner.name,
                    type.h2.copy(fontWeight = FontWeight.Bold),
                    colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                WinTag(winLabel(winner))
                if (show.multi) {
                    Spacer(Modifier.width(6.dp))
                    WinTag("一炮多响 ${show.index + 1}/${show.winners.size}")
                }
                Spacer(Modifier.weight(1f))
                MjText(
                    "总番 ${winner.totalFan}",
                    type.h2.copy(fontWeight = FontWeight.Bold),
                    colors.ink,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .border(2.dp, colors.tableGold.copy(alpha = frame), RoundedCornerShape(12.dp))
                    .background(colors.surface2.copy(alpha = 0.6f * frame))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                WinnerHand(winner)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
                    .clipToBounds(),
            ) {
                if (show.stage == WinStage.FAN) {
                    WinFanPanel(winner = winner, modeLabel = modeLabel)
                }
            }
        }
    }
}

@Composable
private fun WinTag(text: String) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.tableGold)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        MjText(
            text,
            type.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
            colors.tableGoldInk,
            maxLines = 1,
        )
    }
}

@Composable
private fun WinnerHand(winner: WinnerUi) {
    val colors = LocalMjColors.current
    val meldGap = 7.dp
    BoxWithConstraints {
        val total = (winner.handTiles.size + winner.melds.sumOf { it.tiles.size }).coerceAtLeast(1)
        val available = maxWidth - meldGap * winner.melds.size - 2.dp * (total - 1)
        val tileW = (available / total).coerceIn(12.dp, 34.dp)
        val tileH = tileW * 4f / 3f
        val winIndex = winner.handTiles.indexOfLast { it == winner.winningTile }
        Row(verticalAlignment = Alignment.Bottom) {
            winner.handTiles.forEachIndexed { index, tile ->
                val isWin = index == winIndex
                Box(
                    Modifier
                        .padding(horizontal = 1.dp)
                        .then(
                            if (isWin) {
                                Modifier.border(1.5.dp, colors.tableGold, RoundedCornerShape(6.dp)).padding(1.dp)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    MjTile(
                        tile = tile,
                        width = tileW,
                        height = tileH,
                        visual = if (isWin) TileVisual.WINNABLE else TileVisual.NORMAL,
                    )
                }
            }
            winner.melds.forEach { meld ->
                Spacer(Modifier.width(meldGap))
                Row {
                    meld.tiles.forEach { tile ->
                        MjTile(tile = tile, width = tileW * 0.92f, height = tileH * 0.92f)
                    }
                }
            }
        }
    }
}

@Composable
private fun WinFanPanel(winner: WinnerUi, modeLabel: String) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val reduce = LocalReduceMotion.current
    val isMcr = modeLabel.contains("国标")
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(
                if (isMcr) "番种明细" else "番数构成",
                type.label.copy(fontWeight = FontWeight.SemiBold),
                colors.ink,
            )
            Spacer(Modifier.weight(1f))
            MjText(modeLabel, type.caption.copy(fontSize = 9.sp), colors.ink3, maxLines = 1)
        }
        Spacer(Modifier.height(3.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            winner.fans.forEachIndexed { rowIndex, fan ->
                var shown by remember { mutableStateOf(reduce) }
                LaunchedEffect(Unit) { shown = true }
                val alpha by animateFloatAsState(
                    targetValue = if (shown) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (reduce) 0 else 140,
                        delayMillis = if (reduce) 0 else rowIndex * 40,
                    ),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { this.alpha = alpha }
                        .padding(vertical = 1.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MjText(
                        if (fan.count > 1) "${fan.name} ×${fan.count}" else fan.name,
                        type.caption.copy(fontSize = 10.sp),
                        colors.ink,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    MjText(
                        if (fan.fan == 0) "—" else "${fan.fan} 番",
                        type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        when {
                            fan.fan >= 6 -> colors.gold
                            fan.fan == 0 -> colors.ink3
                            else -> colors.ink2
                        },
                        maxLines = 1,
                    )
                }
            }
            if (winner.fans.isEmpty()) {
                MjText("无番种记录", type.caption.copy(fontSize = 10.sp), colors.ink3)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(
                "基本分 ${winner.points}",
                type.caption.copy(fontSize = 9.sp),
                colors.ink3,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            MjText(
                "总番 ${winner.totalFan} 番",
                type.label.copy(fontWeight = FontWeight.Bold),
                colors.ink,
                maxLines = 1,
            )
        }
    }
}

private fun winLabel(winner: WinnerUi): String {
    val names = winner.fans.map { it.name }
    return when {
        winner.robKong || names.any { it.contains("抢杠") } -> "抢杠"
        winner.selfDraw && names.any { it.contains("杠上开花") } -> "杠上开花"
        winner.selfDraw -> "自摸"
        else -> "点炮"
    }
}

@Composable
private fun BannerOverlay(banner: Banner?, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier,
        enter = fadeIn(tween(if (reduceMotion) 0 else MjMotion.micro)) +
            scaleIn(tween(if (reduceMotion) 0 else MjMotion.small), initialScale = 0.72f),
        exit = fadeOut(tween(if (reduceMotion) 0 else MjMotion.medium)) +
            scaleOut(tween(if (reduceMotion) 0 else MjMotion.medium), targetScale = 0.96f),
    ) {
        val text = banner?.text ?: ""
        val sub = banner?.sub ?: ""
        val isWin = banner?.kind == BannerKind.HU
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Box(
                Modifier
                    .size(if (isWin) 104.dp else 84.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = if (isWin) 0.5f else 0.38f))
                    .border(
                        if (isWin) 2.dp else 1.dp,
                        colors.tableGold.copy(alpha = if (isWin) 0.95f else 0.6f),
                        RoundedCornerShape(999.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MjText(
                    text,
                    LocalMjType.current.display.copy(
                        fontSize = if (isWin) 38.sp else 30.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    if (isWin) colors.tableGold else colors.onTable,
                )
            }
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                MjText(
                    sub,
                    LocalMjType.current.label.copy(fontWeight = FontWeight.SemiBold),
                    if (isWin) colors.tableGold else colors.onTable2,
                )
            }
        }
    }
}

@Composable
private fun ChiSheet(
    options: List<List<Int>>,
    claimed: Int?,
    onDismiss: () -> Unit,
    onPick: (List<Int>) -> Unit,
) {
    val colors = LocalMjColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(bottom = 90.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(MjRadius.card))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(MjRadius.card))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MjText("选择吃牌组合", LocalMjType.current.title, colors.ink)
            Spacer(Modifier.height(10.dp))
            options.forEach { tiles ->
                val shown = if (claimed != null) (tiles + claimed).sorted() else tiles.sorted()
                Row(
                    Modifier
                        .clip(RoundedCornerShape(MjRadius.small))
                        .clickable { onPick(tiles) }
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    shown.forEach { MjTile(tile = it, width = 26.dp, height = 34.dp) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ExitDialog(onContinue: () -> Unit, onExit: () -> Unit) {
    MjDialog(onDismiss = onContinue) {
        DialogTitle("退出本局？")
        Spacer(Modifier.height(6.dp))
        DialogMessage("当前牌局进度会被放弃，本局不计入战绩。")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("继续打牌", onContinue, modifier = Modifier.weight(1f), height = 44.dp)
            PrimaryButton("退出", onExit, modifier = Modifier.weight(1f), height = 44.dp)
        }
    }
}

// ------------------------------------------------------------ 小工具

private fun claimLabel(option: ClaimOption): String = when (option) {
    ClaimOption.Hu -> "胡"
    ClaimOption.Peng -> "碰"
    ClaimOption.Gang -> "杠"
    ClaimOption.Pass -> "过"
    is ClaimOption.Chi -> "吃"
}

private fun seatPositions(meSeat: Int): IntArray {
    // 0=自己(下)、1=右、2=对、3=左
    return intArrayOf(meSeat, (meSeat + 1) % 4, (meSeat + 2) % 4, (meSeat + 3) % 4)
}
