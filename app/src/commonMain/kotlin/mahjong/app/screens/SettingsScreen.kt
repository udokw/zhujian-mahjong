package mahjong.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.platformAppVersion
import mahjong.app.platformDefaultServerUrl
import mahjong.app.settings.AiSpeed
import mahjong.app.settings.AppSettings
import mahjong.app.settings.SettingsRepository
import mahjong.app.settings.TileScale
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MjRadius
import mahjong.app.theme.MjSpace
import mahjong.app.theme.TableSkin
import mahjong.app.theme.ThemeMode
import mahjong.app.theme.mjColors
import mahjong.app.ui.ChevronRow
import mahjong.app.ui.ChoiceRow
import mahjong.app.ui.DividerLine
import mahjong.app.ui.MjCard
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjSwitch
import mahjong.app.ui.MjText
import mahjong.app.ui.Segmented
import mahjong.app.ui.SettingRow
import mahjong.app.ui.SettingsGroup
import mahjong.app.util.drawPageBackdrop
import mahjong.app.util.drawTableFelt
import mahjong.core.ai.AiLevel
import mahjong.core.rules.GameMode
import mahjong.core.rules.RulePreset

private val presetList = listOf(RulePreset.MCR_1998, RulePreset.GD_TUIDAOHU, RulePreset.GD_JIPINGHU)

private fun presetName(preset: RulePreset): String = when (preset) {
    RulePreset.MCR_1998 -> "国标麻将"
    RulePreset.GD_TUIDAOHU -> "广东推倒胡"
    RulePreset.GD_JIPINGHU -> "广东鸡平胡"
}

private fun presetSub(preset: RulePreset): String = when (preset) {
    RulePreset.MCR_1998 -> "8 番起和，花牌计番，只允许单响"
    RulePreset.GD_TUIDAOHU -> "只可自摸，无花牌，带买马"
    RulePreset.GD_JIPINGHU -> "带花牌，可一炮多响，漏胡与包牌"
}

private fun levelName(level: AiLevel): String = when (level) {
    AiLevel.NOVICE -> "新手"
    AiLevel.NORMAL -> "普通"
    AiLevel.EXPERT -> "高手"
    AiLevel.MASTER -> "大师"
}

private fun levelSub(level: AiLevel): String = when (level) {
    AiLevel.NOVICE -> "失误较多，适合熟悉规则"
    AiLevel.NORMAL -> "兼顾牌效率与基础防守"
    AiLevel.EXPERT -> "读牌河、算剩余张数"
    AiLevel.MASTER -> "精确进张 + 番型规划"
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(Modifier.fillMaxSize().drawPageBackdrop(colors)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    MjIcon(MjIconKind.BACK, size = 18.dp, tint = colors.ink2)
                }
                Spacer(Modifier.width(4.dp))
                MjText("设置", type.title, colors.ink)
            }

            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = MjSpace.page),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // 列 1：显示
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                Spacer(Modifier.height(6.dp))
                MjText("显示", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                Spacer(Modifier.height(8.dp))
                MjCard {
                    MjText("主题", type.bodyStrong, colors.ink)
                    Spacer(Modifier.height(8.dp))
                    Segmented(
                        options = ThemeMode.entries.map { it.label },
                        selectedIndex = ThemeMode.entries.indexOf(settings.theme),
                        onSelect = { onChange(settings.copy(theme = ThemeMode.entries[it])) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeSwatch(
                            mode = ThemeMode.DAY,
                            skin = settings.skin,
                            selected = settings.theme == ThemeMode.DAY,
                            modifier = Modifier.weight(1f),
                            onClick = { onChange(settings.copy(theme = ThemeMode.DAY)) },
                        )
                        ThemeSwatch(
                            mode = ThemeMode.NIGHT,
                            skin = settings.skin,
                            selected = settings.theme == ThemeMode.NIGHT,
                            modifier = Modifier.weight(1f),
                            onClick = { onChange(settings.copy(theme = ThemeMode.NIGHT)) },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    MjText("牌桌皮肤", type.bodyStrong, colors.ink)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TableSkin.entries.forEach { skin ->
                            SkinSwatch(
                                skin = skin,
                                selected = settings.skin == skin,
                                dark = colors.isDark,
                                modifier = Modifier.weight(1f),
                                onClick = { onChange(settings.copy(skin = skin)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    MjText("牌面大小", type.bodyStrong, colors.ink)
                    Spacer(Modifier.height(8.dp))
                    Segmented(
                        options = listOf("标准", "放大"),
                        selectedIndex = if (settings.tileScale == TileScale.LARGE) 1 else 0,
                        onSelect = {
                            onChange(settings.copy(tileScale = if (it == 1) TileScale.LARGE else TileScale.STANDARD))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    SettingRow(
                        title = "减弱动效",
                        subtitle = "关闭碰杠、结算的位移动画",
                        showDivider = false,
                    ) {
                        MjSwitch(settings.reduceMotion, onChange = { onChange(settings.copy(reduceMotion = it)) })
                    }
                }

                }
                // 列 2：规则
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                Spacer(Modifier.height(6.dp))
                MjText("规则", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                Spacer(Modifier.height(8.dp))
                SettingsGroup("AI 难度", "只影响之后的牌局") {
                    AiLevel.entries.forEachIndexed { index, level ->
                        ChoiceRow(
                            title = levelName(level),
                            subtitle = levelSub(level),
                            selected = settings.aiLevel == level,
                            onClick = { onChange(settings.copy(aiLevelId = level.id)) },
                            showDivider = index != AiLevel.entries.lastIndex,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingsGroup("默认玩法") {
                    presetList.forEachIndexed { index, preset ->
                        ChoiceRow(
                            title = presetName(preset),
                            subtitle = presetSub(preset),
                            selected = settings.preset == preset,
                            onClick = { onChange(settings.copy(rulePresetId = preset.id)) },
                            showDivider = index != presetList.lastIndex,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingsGroup("规则开关", "部分开关受玩法限制") {
                    val isMcr = settings.preset.modeOrDefault() == GameMode.MCR
                    SettingRow("花牌计番", "关闭后花牌不参与计番") {
                        MjSwitch(settings.flowerFan, onChange = { onChange(settings.copy(flowerFan = it)) })
                    }
                    SettingRow("抢杠胡", "他人补杠时可抢杠和牌") {
                        MjSwitch(settings.robKong, onChange = { onChange(settings.copy(robKong = it)) })
                    }
                    SettingRow(
                        "一炮多响",
                        if (isMcr) "国标麻将固定为单响" else "多家同时可胡时全部结算",
                    ) {
                        MjSwitch(
                            settings.multipleWinners && !isMcr,
                            onChange = { onChange(settings.copy(multipleWinners = it)) },
                        )
                    }
                    SettingRow("包牌", "点炮者承担全部输分") {
                        MjSwitch(settings.baoPai, onChange = { onChange(settings.copy(baoPai = it)) })
                    }
                    SettingRow("听牌提示", "牌桌显示可胡与听牌提示", showDivider = false) {
                        MjSwitch(settings.hintHu, onChange = { onChange(settings.copy(hintHu = it)) })
                    }
                }

                }
                // 列 3：体验
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                Spacer(Modifier.height(6.dp))
                MjText("体验", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                Spacer(Modifier.height(8.dp))
                MjCard {
                    MjText("AI 出牌速度", type.bodyStrong, colors.ink)
                    Spacer(Modifier.height(2.dp))
                    MjText("摸牌停顿、出牌与报牌整链一起放慢，重启后保留", type.caption, colors.ink3)
                    Spacer(Modifier.height(8.dp))
                    Segmented(
                        options = AiSpeed.entries.map { it.label },
                        selectedIndex = AiSpeed.entries.indexOf(settings.aiSpeed),
                        onSelect = { onChange(settings.copy(aiSpeed = AiSpeed.entries[it])) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    SettingRow("音效", "敲桌、摸牌、吃碰杠、胡牌与结算") {
                        MjSwitch(settings.sound, onChange = { onChange(settings.copy(sound = it)) })
                    }
                    SettingRow("报牌语音", "出牌时念出牌名，离线可用") {
                        MjSwitch(settings.voice, onChange = { onChange(settings.copy(voice = it)) })
                    }
                    SettingRow("震动", "操作反馈", showDivider = false) {
                        MjSwitch(settings.haptics, onChange = { onChange(settings.copy(haptics = it)) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MjText("音量", type.body, colors.ink)
                        Spacer(Modifier.width(12.dp))
                        Slider(
                            value = settings.volume,
                            onValueChange = { onChange(settings.copy(volume = it)) },
                            modifier = Modifier.weight(1f),
                            enabled = settings.sound,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.primary,
                                activeTrackColor = colors.primary,
                                inactiveTrackColor = colors.surface3,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        MjText(
                            "${(settings.volume * 100).toInt()}%",
                            type.caption,
                            if (settings.sound) colors.ink2 else colors.disabled,
                            modifier = Modifier.width(38.dp),
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))
                MjText("联机", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                Spacer(Modifier.height(8.dp))
                MjCard {
                    MjText("昵称", type.body, colors.ink)
                    Spacer(Modifier.height(6.dp))
                    FieldBox(
                        value = settings.nickname,
                        placeholder = "输入昵称",
                        onValueChange = { onChange(settings.copy(nickname = it.take(12))) },
                    )
                    Spacer(Modifier.height(16.dp))
                    MjText("服务器地址", type.body, colors.ink)
                    Spacer(Modifier.height(2.dp))
                    MjText("留空使用默认地址 ${platformDefaultServerUrl()}", type.caption, colors.ink3)
                    Spacer(Modifier.height(6.dp))
                    FieldBox(
                        value = settings.serverUrl,
                        placeholder = "ws://host:port/ws",
                        onValueChange = { onChange(settings.copy(serverUrl = it.trim())) },
                    )
                }

                Spacer(Modifier.height(22.dp))
                MjText("关于", type.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
                Spacer(Modifier.height(8.dp))
                MjCard {
                    ChevronRow("版本", "v${platformAppVersion()}") { onToast("当前已是最新版本") }
                    DividerLine()
                    ChevronRow("规则说明", null) { onToast("国标 1998 · 广东推倒胡 · 广东鸡平胡") }
                    DividerLine()
                    ChevronRow("恢复默认设置", null) {
                        onChange(AppSettings())
                        onToast("已恢复默认设置")
                    }
                }

                Spacer(Modifier.height(24.dp))
                MjText(
                    "设置会立即生效并保存在本机，不上传服务器。",
                    type.caption,
                    colors.ink3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

private fun RulePreset.modeOrDefault(): GameMode =
    if (this == RulePreset.MCR_1998) GameMode.MCR else GameMode.GUANGDONG

@Composable
private fun SkinSwatch(
    skin: TableSkin,
    selected: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val preview = mjColors(dark, skin)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MjRadius.small))
            .background(colors.surface2)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) colors.primary else colors.line,
                RoundedCornerShape(MjRadius.small),
            )
            .clickable { onClick() }
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(preview.table2),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(preview.tileFace),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(skin.label, type.caption, if (selected) colors.primaryDeep else colors.ink2, maxLines = 1)
            if (selected) {
                Spacer(Modifier.width(3.dp))
                MjIcon(MjIconKind.CHECK, size = 11.dp, tint = colors.primary)
            }
        }
    }
}

/** 日间 / 夜间主题的实物预览：直接画一块缩小牌桌，而不是只有一个色块。 */
@Composable
private fun ThemeSwatch(
    mode: ThemeMode,
    skin: TableSkin,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val preview = mjColors(mode == ThemeMode.NIGHT, skin)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MjRadius.small))
            .background(colors.surface2)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) colors.primary else colors.line,
                RoundedCornerShape(MjRadius.small),
            )
            .clickable { onClick() }
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .drawTableFelt(preview),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(mode.label, type.caption, if (selected) colors.primaryDeep else colors.ink2, maxLines = 1)
            if (selected) {
                Spacer(Modifier.width(3.dp))
                MjIcon(MjIconKind.CHECK, size = 11.dp, tint = colors.primary)
            }
        }
    }
}

@Composable
private fun FieldBox(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MjRadius.small))
            .background(colors.surface2)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) {
            MjText(placeholder, type.body, colors.disabled, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(
                color = colors.ink,
                fontSize = type.body.fontSize,
                lineHeight = type.body.lineHeight,
            ),
            cursorBrush = SolidColor(colors.primary),
        )
    }
}
