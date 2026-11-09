package mahjong.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                // 主题预览整体缩小约 30%，避免它在左侧抢走视觉重心；
                // 用可伸缩容器，窄屏时预览先让位，版本号不会被挤出屏幕。
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TablePreview(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .heightIn(max = 104.dp)
                            .fillMaxHeight()
                            .align(Alignment.TopStart),
                    )
                }

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
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MjIcon(MjIconKind.ROBOT, size = 15.dp, tint = colors.ink3)
                                Spacer(Modifier.width(6.dp))
                                MjText(
                                    "人机练习",
                                    type.label.copy(fontWeight = FontWeight.SemiBold),
                                    colors.ink2,
                                )
                            }
                            InfoRow("底分", "${arena.baseScore}")
                            InfoRow(
                                "AI 强度",
                                levelLabel(settings.aiLevel),
                                onClick = { showLevelSheet = true },
                            )
                            Spacer(Modifier.weight(1f))
                            PrimaryButton(
                                label = "开始牌局",
                                onClick = onQuickStart,
                                height = 52.dp,
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
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 268.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MjText(
                        "常见牌型：牌面是一副可胡的摆法，名称后为番值",
                        type.caption.copy(fontSize = 9.sp),
                        colors.ink3,
                    )
                    Spacer(Modifier.height(8.dp))
                    PatternGrid(
                        listOf(
                            PatternSpec(
                                "十三幺", "88 番",
                                listOf(0, 0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33),
                                "十三种幺九各一张，其中一种再配一张成对",
                            ),
                            PatternSpec(
                                "大三元", "88 番",
                                listOf(31, 31, 31, 32, 32, 32, 33, 33, 33, 9, 10, 11, 22, 22),
                                "中、发、白各一副刻子，其余顺子加一对将",
                            ),
                            PatternSpec(
                                "大四喜", "88 番",
                                listOf(27, 27, 27, 28, 28, 28, 29, 29, 29, 30, 30, 30, 31, 31),
                                "东、南、西、北各一副刻子，另加一对将",
                            ),
                            PatternSpec(
                                "绿一色", "88 番",
                                listOf(19, 19, 19, 20, 20, 20, 21, 21, 21, 23, 23, 23, 25, 25),
                                "只由二三四六八条和发财组成",
                            ),
                            PatternSpec(
                                "九莲宝灯", "88 番",
                                listOf(0, 0, 0, 1, 2, 3, 4, 4, 5, 6, 7, 8, 8, 8),
                                "同花色 1112345678999，再摸到该花色任意一张",
                            ),
                            PatternSpec(
                                "清幺九", "64 番",
                                listOf(0, 0, 0, 8, 8, 8, 9, 9, 9, 17, 17, 17, 18, 18),
                                "所有牌都是一或九",
                            ),
                            PatternSpec(
                                "字一色", "64 番",
                                listOf(27, 27, 27, 28, 28, 28, 31, 31, 31, 32, 32, 32, 33, 33),
                                "所有牌都是字牌",
                            ),
                            PatternSpec(
                                "小四喜", "64 番",
                                listOf(27, 27, 27, 28, 28, 28, 29, 29, 29, 30, 30, 31, 31, 31),
                                "三副风刻子，第四种风作将",
                            ),
                            PatternSpec(
                                "小三元", "64 番",
                                listOf(31, 31, 31, 32, 32, 32, 33, 33, 0, 1, 2, 9, 10, 11),
                                "两副箭刻子，第三种箭作将",
                            ),
                            PatternSpec(
                                "七对", "24 番",
                                listOf(0, 0, 3, 3, 9, 9, 13, 13, 18, 18, 27, 27, 33, 33),
                                "七组对子，不需要顺子或刻子",
                            ),
                            PatternSpec(
                                "清一色", "24 番",
                                listOf(0, 0, 1, 1, 2, 2, 3, 4, 4, 4, 5, 6, 7, 8),
                                "全部是同一花色",
                            ),
                            PatternSpec(
                                "碰碰和", "6 番",
                                listOf(0, 0, 0, 1, 1, 1, 10, 10, 10, 21, 21, 21, 26, 26),
                                "四副刻子加一对将",
                            ),
                            PatternSpec(
                                "混一色", "6 番",
                                listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 27, 27, 27, 31, 31),
                                "一种花色加字牌",
                            ),
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    MjText(
                        "平和、断幺、自摸、花牌是番种而不是固定牌型，所以不配牌面图：平和为四副顺子加数牌将，断幺不含幺九与字牌，自摸与花牌按次/张加番。",
                        type.caption.copy(fontSize = 9.sp),
                        colors.ink3,
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
                }
            }
        }
    }
}

/** 好友房入口：浅色底 + 描边，名称下方是三组示例牌型。 */
@Composable
private fun FriendRoomEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MjRadius.card))
            .background(colors.primarySoft)
            .border(1.dp, colors.primary.copy(alpha = 0.18f), RoundedCornerShape(MjRadius.card))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText("好友房", type.h2, colors.primaryDeep, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            MjIcon(MjIconKind.CHEVRON_RIGHT, size = 15.dp, tint = colors.primaryDeep.copy(alpha = 0.75f))
        }
        Spacer(Modifier.height(3.dp))
        MjText(
            "局域网四人同桌 · 房号邀请",
            type.caption.copy(fontSize = 9.sp),
            colors.primaryDeep.copy(alpha = 0.66f),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        // 三行示例牌型：十三幺 / 大三元 / 大四喜，按卡片实际宽度均分牌宽。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 1.5.dp
            val tileWidth = ((maxWidth - gap * 13) / 14).coerceIn(8.dp, 16.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                friendEntryPatterns.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { MjTile(it, width = tileWidth) }
                    }
                }
            }
        }
    }
}

/** 好友房入口的三行牌：十三幺、大三元、大四喜，各 14 张。 */
private val friendEntryPatterns: List<List<Int>> = listOf(
    // 十三幺：十三种幺九各一张，一万成对作将。
    listOf(0, 0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33),
    // 大三元：中中中 发发发 白白白 一二三筒 五五条。
    listOf(31, 31, 31, 32, 32, 32, 33, 33, 33, 9, 10, 11, 22, 22),
    // 大四喜：东东东 南南南 西西西 北北北 中中。
    listOf(27, 27, 27, 28, 28, 28, 29, 29, 29, 30, 30, 30, 31, 31),
)

/** 人机练习参数行：左标签右数值，整行铺满，避免右栏出现大片留白。 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MjRadius.control))
            .background(colors.surface2)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MjText(label, type.caption.copy(fontSize = 10.sp), colors.ink3, maxLines = 1)
        Spacer(Modifier.weight(1f))
        MjText(value, type.bodyStrong, colors.ink, maxLines = 1)
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            MjIcon(MjIconKind.CHEVRON_RIGHT, size = 12.dp, tint = colors.ink3)
        }
    }
}

/** 玩法里的一个胡牌牌型：牌面示例 + 番值 + 一句话说明。 */
private data class PatternSpec(
    val name: String,
    val fan: String,
    val tiles: List<Int>,
    val note: String,
)

/** 双栏排布牌型图，一行两个，窄屏也能放下 14 张牌。 */
@Composable
private fun PatternGrid(patterns: List<PatternSpec>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        patterns.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                row.forEach { spec ->
                    PatternCell(spec, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PatternCell(spec: PatternSpec, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(spec.name, type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.primarySoft)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                MjText(spec.fan, type.caption.copy(fontSize = 8.sp), colors.primaryDeep, maxLines = 1)
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            spec.tiles.forEach { MjTile(it, width = 18.dp) }
        }
        Spacer(Modifier.height(3.dp))
        MjText(spec.note, type.caption.copy(fontSize = 9.sp), colors.ink3, maxLines = 2)
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
            .heightIn(min = 72.dp)
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
