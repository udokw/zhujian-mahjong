package mahjong.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.platformAppVersion
import mahjong.app.settings.AppSettings
import mahjong.app.settings.Arenas
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MjRadius
import mahjong.app.ui.Avatar
import mahjong.app.ui.ChoiceRow
import mahjong.app.ui.GhostButton
import mahjong.app.ui.IconSquare
import mahjong.app.ui.MjCard
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjText
import mahjong.app.ui.MjTile
import mahjong.app.ui.ModalSheet
import mahjong.app.ui.PrimaryButton
import mahjong.app.ui.Segmented
import mahjong.app.ui.SheetTitle
import mahjong.app.util.drawPageBackdrop
import mahjong.app.util.drawTableFelt
import mahjong.core.ai.AiLevel
import mahjong.core.rules.RulePreset

private val presetOptions = listOf(RulePreset.MCR_1998, RulePreset.GD_TUIDAOHU, RulePreset.GD_JIPINGHU)

private fun presetRules(preset: RulePreset): String = when (preset) {
    RulePreset.MCR_1998 -> "8 番起和 · 花牌计番 · 单响"
    RulePreset.GD_TUIDAOHU -> "只可自摸 · 无花牌 · 带买马"
    RulePreset.GD_JIPINGHU -> "带花牌 · 可一炮多响 · 漏胡与包牌"
}

private fun levelLabel(level: AiLevel): String = when (level) {
    AiLevel.NOVICE -> "入门"
    AiLevel.NORMAL -> "熟练"
    AiLevel.EXPERT -> "老练"
    AiLevel.MASTER -> "大师"
}

private fun levelDesc(level: AiLevel): String = when (level) {
    AiLevel.NOVICE -> "会基本出牌，几乎不防守"
    AiLevel.NORMAL -> "兼顾牌效率与基础防守"
    AiLevel.EXPERT -> "会读牌河、计算剩余张数"
    AiLevel.MASTER -> "全力计算，适合进阶对局"
}

@Composable
fun LobbyScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onQuickStart: () -> Unit,
    onFriendRoom: () -> Unit,
    onSettings: () -> Unit,
    onRecords: () -> Unit,
    onResumeTable: () -> Unit,
    inGame: Boolean,
    onToast: (String) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    var showLevelSheet by remember { mutableStateOf(false) }
    var showRulesSheet by remember { mutableStateOf(false) }
    val arena = Arenas[settings.arenaIndex.coerceIn(0, Arenas.lastIndex)]
    val presetIndex = presetOptions.indexOf(settings.preset).coerceAtLeast(0)

    Box(
        Modifier
            .fillMaxSize()
            .drawPageBackdrop(colors)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // ---- 左侧身份与导航
            Column(Modifier.width(226.dp).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(settings.nickname, size = 38.dp, large = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        MjText(settings.nickname, type.label, colors.ink, maxLines = 1)
                        MjText("竹青三段 · 1,280 分", type.caption.copy(fontSize = 9.sp), colors.ink3, maxLines = 1)
                    }
                    IconSquare(MjIconKind.RECORD, onRecords)
                }
                Spacer(Modifier.height(14.dp))
                MjText("竹间麻将", type.h1, colors.ink)
                Spacer(Modifier.height(2.dp))
                MjText("四人成局，清风入席", type.caption, colors.ink3)

                if (inGame) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MjRadius.control))
                            .background(colors.primarySoft)
                            .clickable { onResumeTable() }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.primary),
                        )
                        Spacer(Modifier.width(8.dp))
                        MjText("牌局进行中", type.caption.copy(fontWeight = FontWeight.SemiBold), colors.primaryDeep)
                        Spacer(Modifier.weight(1f))
                        MjText("回到牌桌", type.caption, colors.primaryDeep)
                    }
                }

                Spacer(Modifier.height(14.dp))
                RuleSummary("起和", if (settings.preset == RulePreset.MCR_1998) "8 番" else "0 番 / 鸡胡")
                RuleSummary("花牌", if (settings.flowerFan) "计番" else "不计")
                RuleSummary("一炮多响", if (settings.multipleWinners) "允许" else "单响")
                RuleSummary("买马", if (settings.preset == RulePreset.GD_TUIDAOHU) "开启" else "—")

                Spacer(Modifier.height(12.dp))
                TablePreview(Modifier.weight(1f).fillMaxWidth())

                Spacer(Modifier.height(10.dp))
                MjText(
                    "记录与设置保存在本机 · v${platformAppVersion()}",
                    type.caption.copy(fontSize = 8.sp),
                    colors.ink3,
                    maxLines = 2,
                )
            }

            Spacer(Modifier.width(16.dp))

            // ---- 右侧：玩法、场次、快速开局
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(MjRadius.control))
                            .clickable { showRulesSheet = true }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MjText("玩法", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                        Spacer(Modifier.width(2.dp))
                        MjIcon(MjIconKind.CHEVRON_RIGHT, size = 13.dp, tint = colors.ink3)
                    }
                    Spacer(Modifier.width(10.dp))
                    Segmented(
                        options = listOf("国标", "推倒胡", "鸡平胡"),
                        selectedIndex = presetIndex,
                        onSelect = { index ->
                            onChange(settings.copy(rulePresetId = presetOptions[index].id))
                        },
                        modifier = Modifier.weight(1f),
                        height = 38.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    // 齿轮接管原 AI 强度胶囊的位置：同为 38dp 高，中心自然对齐。
                    IconSquare(MjIconKind.GEAR, onSettings)
                }
                Spacer(Modifier.height(6.dp))
                MjText(presetRules(settings.preset), type.caption.copy(fontSize = 9.sp), colors.ink3)

                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MjText("场次", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (index in Arenas.indices.take(4)) {
                            ArenaChip(
                                index = index,
                                selected = settings.arenaIndex == index,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onChange(
                                        settings.copy(
                                            arenaIndex = index,
                                            aiLevelId = Arenas[index].aiLevel.id,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                MjCard(Modifier.weight(1f).fillMaxWidth(), padding = 12.dp) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 好友房与「开始牌局」左右并排。
                        FriendRoomEntry(
                            onClick = onFriendRoom,
                            modifier = Modifier.weight(1.15f).fillMaxHeight(),
                        )
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.End,
                        ) {
                            // 「人机练习」提示移到开始牌局按钮上方：开始牌局本身就是人机对局入口。
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MjRadius.small))
                                    .clickable { showLevelSheet = true }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MjText(
                                    "人机练习 · 底分 ${arena.baseScore}",
                                    type.caption.copy(fontSize = 9.sp),
                                    colors.ink3,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(6.dp))
                                MjText(
                                    levelLabel(settings.aiLevel),
                                    type.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                                    colors.primaryDeep,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(2.dp))
                                MjIcon(MjIconKind.CHEVRON_RIGHT, size = 12.dp, tint = colors.ink3)
                            }
                            Spacer(Modifier.height(10.dp))
                            PrimaryButton(
                                label = "开始牌局",
                                onClick = onQuickStart,
                                height = 48.dp,
                            )
                        }
                    }
                }
            }
        }

        if (showLevelSheet) {
            ModalSheet(onDismiss = { showLevelSheet = false }) {
                SheetTitle("AI 强度", "四档可随时切换，只影响之后的牌局")
                AiLevel.entries.forEach { level ->
                    ChoiceRow(
                        title = levelLabel(level),
                        subtitle = levelDesc(level),
                        selected = settings.aiLevel == level,
                        onClick = {
                            onChange(settings.copy(aiLevelId = level.id))
                            showLevelSheet = false
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (showRulesSheet) {
            ModalSheet(onDismiss = { showRulesSheet = false }) {
                SheetTitle("胡牌牌型与番数", "国标 8 番起和；广东玩法按 2 的番数次方计分")
                RulesSection(
                    "国标常见番种",
                    listOf(
                        "88 番" to "十三幺、大三元、大四喜、绿一色、九莲宝灯",
                        "64 番" to "清幺九、字一色、小四喜、小三元",
                        "24 番" to "七对、清一色",
                        "6 番" to "碰碰和、混一色",
                        "2 番" to "平和、断幺",
                        "1 番" to "自摸、花牌（每张）",
                    ),
                )
                Spacer(Modifier.height(12.dp))
                RulesSection(
                    "推倒胡 / 鸡平胡",
                    listOf(
                        "1 番" to "平胡",
                        "2 番" to "碰碰胡、混一色、七对",
                        "4 番" to "清一色、混碰、清一色七对",
                        "5 番" to "清碰、小四喜、小三元、混幺九",
                        "6 番" to "十三幺、九莲宝灯、连七对、字一色、大三元、大四喜",
                        "加番" to "自摸 +1 番",
                    ),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 好友房主入口：深色渐变底 + 右侧牌面图案，与「开始牌局」并排。 */
@Composable
private fun FriendRoomEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MjRadius.card))
            .background(Brush.horizontalGradient(listOf(colors.primary, colors.primaryDeep)))
            .clickable { onClick() },
    ) {
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(4, 13, 22).forEach { MjTile(it, width = 26.dp) }
        }
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp, end = 104.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MjText("好友房", type.h1, colors.onPrimary, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                MjIcon(MjIconKind.CHEVRON_RIGHT, size = 16.dp, tint = colors.onPrimary.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(3.dp))
            MjText(
                "局域网四人同桌",
                type.caption,
                colors.onPrimary.copy(alpha = 0.78f),
                maxLines = 1,
            )
        }
    }
}

/** 番数说明里的一组牌型。 */
@Composable
private fun RulesSection(title: String, rows: List<Pair<String, String>>) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    MjText(title, type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
    Spacer(Modifier.height(6.dp))
    rows.forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .width(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.primarySoft)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                MjText(row.first, type.caption.copy(fontSize = 9.sp), colors.primaryDeep, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            MjText(row.second, type.caption, colors.ink2, modifier = Modifier.weight(1f))
        }
    }
}

/** 主页的牌桌缩略预览：木边 + 绒面 + 三张牌，让主题在进入牌桌之前就可见。 */
@Composable
private fun TablePreview(modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(18.dp))
            .drawTableFelt(colors)
            .border(1.dp, colors.tableGold.copy(alpha = 0.30f), RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (tile in listOf(4, 13, 22)) {
                MjTile(tile, width = 24.dp)
            }
        }
        MjText(
            if (colors.isDark) "夜间 · 经典桌" else "日间 · 经典桌",
            type.caption.copy(fontSize = 9.sp),
            colors.onTable2,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun RuleSummary(label: String, value: String) {
    val colors = LocalMjColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MjText(label, LocalMjType.current.caption, colors.ink3)
        Spacer(Modifier.weight(1f))
        MjText(value, LocalMjType.current.caption.copy(fontWeight = FontWeight.SemiBold), colors.ink2, maxLines = 1)
    }
}

@Composable
private fun ArenaChip(
    index: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val arena = Arenas[index]
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(MjRadius.control))
            .background(if (selected) colors.primarySoft else colors.surface)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) colors.primary else colors.line,
                RoundedCornerShape(MjRadius.control),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            MjText(
                arena.name,
                type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                if (selected) colors.primaryDeep else colors.ink2,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                MjText(
                    arena.baseScore.toString(),
                    type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    if (selected) colors.primaryDeep else colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(2.dp))
                MjText(
                    "分底",
                    type.caption.copy(fontSize = 8.sp),
                    colors.ink3,
                    modifier = Modifier.padding(bottom = 1.dp),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (selected) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary),
            )
        }
    }
}
