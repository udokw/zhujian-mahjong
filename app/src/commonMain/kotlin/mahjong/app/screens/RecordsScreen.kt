package mahjong.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.records.GameRecord
import mahjong.app.records.GameRecordsRepository
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MjRadius
import mahjong.app.theme.MjSpace
import mahjong.app.ui.BottomTabBar
import mahjong.app.ui.MjCard
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjText
import mahjong.app.ui.SettingsGroup
import mahjong.app.ui.SettingRow

@Composable
fun RecordsScreen(
    onBack: () -> Unit,
    onLobby: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    // 进入本页时读一次本地存储；打完一局会回到这里重新组合，拿到的是最新列表。
    val records = remember { GameRecordsRepository.load() }
    val total = records.size
    val wins = records.count { it.won }
    val bestFan = records.maxOfOrNull { it.fan } ?: 0

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(horizontal = MjSpace.page),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
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
                    Spacer(Modifier.weight(1f))
                    MjText("战绩", type.title, colors.ink)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(36.dp))
                }

                Spacer(Modifier.height(6.dp))
                MjText("本机战绩", type.display, colors.ink)
                Spacer(Modifier.height(4.dp))
                MjText("只统计本机完成的牌局", type.caption, colors.ink3)

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("总局数", if (total == 0) "0" else total.toString(), "局", Modifier.weight(1f))
                    StatCard(
                        "胜率",
                        if (total == 0) "—" else "${wins * 100 / total}%",
                        "",
                        Modifier.weight(1f),
                    )
                    StatCard("最高番", if (bestFan == 0) "—" else bestFan.toString(), "番", Modifier.weight(1f))
                }

                Spacer(Modifier.height(22.dp))
                SettingsGroup("最近对局", "完成一局后自动记录") {
                    if (records.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(132.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                MjIcon(MjIconKind.RECORD, size = 26.dp, tint = colors.disabled)
                                Spacer(Modifier.height(8.dp))
                                MjText("还没有牌局记录", type.bodyStrong, colors.ink3)
                                Spacer(Modifier.height(2.dp))
                                MjText(
                                    "打完一局人机或好友房后显示在这里",
                                    type.caption,
                                    colors.ink3,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        records.forEachIndexed { index, record ->
                            if (index > 0) {
                                Spacer(Modifier.height(8.dp))
                            }
                            RecordRow(record)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                SettingsGroup("数据说明") {
                    SettingRow("存储位置", "本机，不上传", showDivider = false)
                }

                Spacer(Modifier.height(20.dp))
            }

            BottomTabBar(
                selected = 2,
                onSelect = { index ->
                    when (index) {
                        0, 1 -> onLobby()
                        3 -> onSettings()
                        else -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun RecordRow(record: GameRecord) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val resultColor = when {
        record.draw -> colors.ink3
        record.won -> colors.green
        else -> colors.red
    }
    MjCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MjText(record.result, type.bodyStrong, resultColor, maxLines = 1)
                    // 未胡但正分只可能来自杠分或买马，标注出来避免看起来自相矛盾。
                    if (!record.won && !record.draw && record.delta > 0) {
                        Spacer(Modifier.width(4.dp))
                        MjText("杠分/买马", type.caption.copy(fontSize = 9.sp), colors.ink3, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    MjText(
                        "${record.mode} · ${record.rule}",
                        type.caption.copy(fontSize = 9.sp),
                        colors.ink3,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(3.dp))
                MjText(record.time, type.caption.copy(fontSize = 9.sp), colors.disabled, maxLines = 1)
            }
            if (record.fan > 0) {
                MjText("${record.fan} 番", type.label, colors.gold, maxLines = 1)
                Spacer(Modifier.width(12.dp))
            }
            val sign = if (record.delta > 0) "+" else ""
            MjText(
                "$sign${record.delta}",
                type.num,
                if (record.delta > 0) colors.green else if (record.delta < 0) colors.red else colors.ink3,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    MjCard(modifier = modifier, padding = 12.dp) {
        MjText(label, type.caption, colors.ink3, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MjText(value, type.numLg.copy(fontSize = 24.sp), colors.ink)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.size(2.dp))
                MjText(unit, type.caption.copy(fontSize = 9.sp), colors.ink3, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
