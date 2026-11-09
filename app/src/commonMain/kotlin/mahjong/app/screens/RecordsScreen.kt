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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontWeight
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

/** 列表框最多露出 5 条记录的高度，超出的部分在框内滚动。 */
private val recentListMaxHeight = 32.dp * 5 + 6.dp * 4

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
                Row(verticalAlignment = Alignment.Bottom) {
                    MjText("本机战绩", type.h2, colors.ink)
                    Spacer(Modifier.width(8.dp))
                    MjText(
                        "只统计本机完成的牌局",
                        type.caption.copy(fontSize = 9.sp),
                        colors.ink3,
                    )
                }

                Spacer(Modifier.height(10.dp))
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

                Spacer(Modifier.height(12.dp))
                val visible = records.size.coerceAtMost(5)
                val listHeight = if (visible == 0) 0.dp else 32.dp * visible + 6.dp * (visible - 1)
                MjCard(Modifier.weight(1f, fill = false).fillMaxWidth(), padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MjText(
                            "最近对局",
                            type.label.copy(fontWeight = FontWeight.SemiBold),
                            colors.ink2,
                        )
                        Spacer(Modifier.weight(1f))
                        MjText("最多显示 5 条", type.caption.copy(fontSize = 9.sp), colors.ink3)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (records.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                MjIcon(MjIconKind.RECORD, size = 24.dp, tint = colors.disabled)
                                Spacer(Modifier.height(6.dp))
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
                        // 只让这个框内滚动：最多露出 5 条的高度，页面本身不随记录数拉长。
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = listHeight, max = recentListMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            records.forEachIndexed { index, record ->
                                if (index > 0) {
                                    Spacer(Modifier.height(6.dp))
                                }
                                RecordRow(record)
                            }
                        }
                    }
                }
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
    MjCard(padding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(record.result, type.label, resultColor, maxLines = 1)
            // 未胡但正分只可能来自杠分或买马，标注出来避免看起来自相矛盾。
            if (!record.won && !record.draw && record.delta > 0) {
                Spacer(Modifier.width(4.dp))
                MjText("杠分/买马", type.caption.copy(fontSize = 8.sp), colors.ink3, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            MjText(
                "${record.mode} · ${record.rule} · ${record.time}",
                type.caption.copy(fontSize = 9.sp),
                colors.ink3,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (record.fan > 0) {
                Spacer(Modifier.width(8.dp))
                MjText("${record.fan} 番", type.caption.copy(fontSize = 9.sp), colors.gold, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            val sign = if (record.delta > 0) "+" else ""
            MjText(
                "$sign${record.delta}",
                type.label,
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
    MjCard(modifier = modifier, padding = 10.dp) {
        MjText(label, type.caption.copy(fontSize = 10.sp), colors.ink3, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MjText(value, type.numLg.copy(fontSize = 22.sp), colors.ink)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.size(2.dp))
                MjText(unit, type.caption.copy(fontSize = 9.sp), colors.ink3, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
