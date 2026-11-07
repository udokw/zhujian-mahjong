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
import mahjong.app.ui.Badge
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
                    Spacer(Modifier.width(6.dp))
                    IconSquare(MjIconKind.GEAR, onSettings)
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

                Spacer(Modifier.height(14.dp))
                TablePreview()

                Spacer(Modifier.weight(1f))
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
                    MjText("玩法", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
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
                    Row(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(MjRadius.control))
                            .background(colors.surface)
                            .border(1.dp, colors.line, RoundedCornerShape(MjRadius.control))
                            .clickable { showLevelSheet = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MjIcon(MjIconKind.ROBOT, size = 15.dp, tint = colors.ink3)
                        Spacer(Modifier.width(6.dp))
                        MjText("AI", type.caption.copy(fontSize = 9.sp), colors.ink3)
                        Spacer(Modifier.width(4.dp))
                        MjText(levelLabel(settings.aiLevel), type.caption.copy(fontWeight = FontWeight.SemiBold), colors.primaryDeep)
                    }
                }
                Spacer(Modifier.height(6.dp))
                MjText(presetRules(settings.preset), type.caption.copy(fontSize = 9.sp), colors.ink3)

                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MjText("场次", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                    Spacer(Modifier.weight(1f))
                    MjText("AI 强度随场次提升", type.caption.copy(fontSize = 9.sp), colors.ink3)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (index in Arenas.indices.take(4)) {
                        ArenaCard(
                            index = index,
                            selected = settings.arenaIndex == index,
                            modifier = Modifier.weight(1f).height(92.dp),
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

                Spacer(Modifier.height(10.dp))
                MjCard(Modifier.weight(1f).fillMaxWidth(), padding = 14.dp) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MjText("人机练习", type.h2, colors.ink)
                                Spacer(Modifier.width(7.dp))
                                Badge("本地")
                                Spacer(Modifier.width(6.dp))
                                MjText("底分 ${arena.baseScore}", type.caption, colors.ink3)
                            }
                            Spacer(Modifier.height(3.dp))
                            MjText("一位真人 + 三位 AI，完整计番与结算", type.caption, colors.ink3)
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MjRadius.small))
                                    .clickable { showLevelSheet = true }
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MjIcon(MjIconKind.ROBOT, size = 15.dp, tint = colors.ink3)
                                Spacer(Modifier.width(6.dp))
                                MjText("AI 强度", type.caption, colors.ink2)
                                Spacer(Modifier.width(8.dp))
                                MjText(levelLabel(settings.aiLevel), type.caption.copy(fontWeight = FontWeight.SemiBold), colors.primaryDeep)
                                Spacer(Modifier.width(3.dp))
                                MjIcon(MjIconKind.CHEVRON_RIGHT, size = 13.dp, tint = colors.ink3)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.width(250.dp)) {
                            PrimaryButton(
                                label = "开始牌局",
                                subLabel = "底分 ${arena.baseScore} · ${levelLabel(settings.aiLevel)} AI",
                                onClick = onQuickStart,
                                height = 46.dp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton(
                                    label = "好友房",
                                    onClick = onFriendRoom,
                                    modifier = Modifier.weight(1f),
                                    height = 38.dp,
                                )
                                GhostButton(
                                    label = "残局",
                                    onClick = { onToast("残局练习正在准备中") },
                                    modifier = Modifier.weight(1f),
                                    height = 38.dp,
                                )
                                GhostButton(
                                    label = "设置",
                                    onClick = onSettings,
                                    modifier = Modifier.weight(1f),
                                    height = 38.dp,
                                )
                            }
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
    }
}

/** 主页的牌桌缩略预览：木边 + 绒面 + 三张牌，让主题在进入牌桌之前就可见。 */
@Composable
private fun TablePreview() {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
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
private fun ArenaCard(
    index: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val arena = Arenas[index]
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MjRadius.card))
            .background(if (selected) colors.primarySoft else colors.surface)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) colors.primary else colors.line,
                RoundedCornerShape(MjRadius.card),
            )
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(arena.name, type.caption.copy(fontWeight = FontWeight.SemiBold), if (selected) colors.primaryDeep else colors.ink2, maxLines = 1)
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
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MjText(arena.baseScore.toString(), type.h2, if (selected) colors.primaryDeep else colors.ink)
            Spacer(Modifier.width(3.dp))
            MjText("分底", type.caption.copy(fontSize = 8.sp), colors.ink3, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.weight(1f))
        MjText(arena.entrance, type.caption.copy(fontSize = 8.sp), colors.ink3, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val filled = index + 1
            for (i in 0 until 4) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (i < filled) {
                                if (selected) colors.primary else colors.ink3.copy(alpha = 0.55f)
                            } else {
                                colors.line
                            },
                        ),
                )
            }
        }
    }
}
